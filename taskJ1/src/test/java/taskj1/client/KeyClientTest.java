package taskj1.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import taskj1.crypto.KeyMaterial;
import taskj1.protocol.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class KeyClientTest {
    @TempDir
    Path directory;

    private ExecutorService executor;
    private final List<ServerSocket> servers = new ArrayList<>();

    @BeforeEach
    void startExecutor() {
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stopServers() throws IOException {
        for (ServerSocket server : servers) {
            server.close();
        }
        executor.shutdownNow();
    }

    private int serveOnce(ServerHandler handler) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        servers.add(server);
        executor.submit(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(30_000);
                handler.handle(socket);
            } catch (Exception ignored) {
            }
            return null;
        });
        return server.getLocalPort();
    }

    private interface ServerHandler {
        void handle(Socket socket) throws Exception;
    }

    private static byte[] readUntilNulOrEof(InputStream in) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) > 0) {
            received.write(value);
        }
        received.write(0);
        return received.toByteArray();
    }

    private static byte[] pemBody(Path path, String type) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
        assertEquals("-----BEGIN " + type + "-----", lines.getFirst());
        assertEquals("-----END " + type + "-----", lines.getLast());
        return Base64.getMimeDecoder().decode(String.join("", lines.subList(1, lines.size() - 1)));
    }

    @Test
    void savesKeyAndCertificateAsPem() throws Exception {
        byte[] keyDer = {1, 2, 3, 4};
        byte[] certDer = {5, 6, 7};
        int port = serveOnce(socket -> {
            assertArrayEquals(new byte[]{'a', 'l', 'i', 'c', 'e', 0},
                    readUntilNulOrEof(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            out.write(Protocol.success(new KeyMaterial(keyDer, certDer)));
            out.flush();
        });

        Path prefix = directory.resolve("alice");
        assertTrue(KeyClient.fetch("127.0.0.1", port, "alice", prefix,
                Duration.ZERO, false, 10_000));
        assertArrayEquals(keyDer, pemBody(Path.of(prefix + ".key"), "PRIVATE KEY"));
        assertArrayEquals(certDer, pemBody(Path.of(prefix + ".crt"), "CERTIFICATE"));
    }

    @Test
    void abortAfterSendSendsFullRequestAndCreatesNoFiles() throws Exception {
        var receivedBox = new byte[1][];
        int port = serveOnce(socket -> {
            receivedBox[0] = readUntilNulOrEof(socket.getInputStream());
            Thread.sleep(5_000);
        });

        Path prefix = directory.resolve("bob");
        long start = System.nanoTime();
        assertFalse(KeyClient.fetch("127.0.0.1", port, "bob", prefix,
                Duration.ZERO, true, 10_000));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(4)) < 0,
                "abort-after-send must not wait for the response");
        for (int i = 0; i < 100 && receivedBox[0] == null; i++) {
            Thread.sleep(50);
        }
        assertArrayEquals(new byte[]{'b', 'o', 'b', 0}, receivedBox[0]);
        assertFalse(Files.exists(Path.of(prefix + ".key")));
        assertFalse(Files.exists(Path.of(prefix + ".crt")));
    }

    @Test
    void delayPostponesCompletionAndFileCreation() throws Exception {
        int port = serveOnce(socket -> {
            readUntilNulOrEof(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(Protocol.success(new KeyMaterial(new byte[]{1}, new byte[]{2})));
            out.flush();
        });

        Path prefix = directory.resolve("slow");
        long start = System.nanoTime();
        assertTrue(KeyClient.fetch("127.0.0.1", port, "slow", prefix,
                Duration.ofSeconds(1), false, 10_000));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofMillis(950)) >= 0,
                "fetch returned before the requested delay elapsed");
        assertTrue(Files.exists(Path.of(prefix + ".key")));
        assertTrue(Files.exists(Path.of(prefix + ".crt")));
    }

    @Test
    void truncatedResponseCreatesNoFiles() throws Exception {
        byte[] full = Protocol.success(new KeyMaterial(new byte[]{1, 2}, new byte[]{3, 4}));
        int port = serveOnce(socket -> {
            readUntilNulOrEof(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(full, 0, full.length / 2);
            out.flush();
        });

        Path prefix = directory.resolve("cut");
        assertThrows(IOException.class, () -> KeyClient.fetch("127.0.0.1", port, "cut", prefix,
                Duration.ZERO, false, 10_000));
        assertFalse(Files.exists(Path.of(prefix + ".key")));
        assertFalse(Files.exists(Path.of(prefix + ".crt")));
    }

    @Test
    void serverErrorCreatesNoFiles() throws Exception {
        int port = serveOnce(socket -> {
            readUntilNulOrEof(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(Protocol.error("Key generation failed; retry the request"));
            out.flush();
        });

        Path prefix = directory.resolve("bad");
        IOException failure = assertThrows(IOException.class,
                () -> KeyClient.fetch("127.0.0.1", port, "bad", prefix,
                        Duration.ZERO, false, 10_000));
        assertTrue(failure.getMessage().contains("retry"), failure.getMessage());
        assertFalse(Files.exists(Path.of(prefix + ".key")));
        assertFalse(Files.exists(Path.of(prefix + ".crt")));
    }

    @Test
    void existingKeyFileIsPreserved() throws Exception {
        int port = serveOnce(socket -> {
            readUntilNulOrEof(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(Protocol.success(new KeyMaterial(new byte[]{1}, new byte[]{2})));
            out.flush();
        });

        Path prefix = directory.resolve("taken");
        Path keyPath = Path.of(prefix + ".key");
        Files.write(keyPath, "sentinel".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> KeyClient.fetch("127.0.0.1", port, "taken", prefix,
                Duration.ZERO, false, 10_000));
        assertEquals("sentinel", Files.readString(keyPath, StandardCharsets.US_ASCII));
        assertFalse(Files.exists(Path.of(prefix + ".crt")));
    }

    @Test
    void newKeyIsRolledBackWhenCertificateExists() throws Exception {
        int port = serveOnce(socket -> {
            readUntilNulOrEof(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(Protocol.success(new KeyMaterial(new byte[]{1}, new byte[]{2})));
            out.flush();
        });

        Path prefix = directory.resolve("half");
        Path certPath = Path.of(prefix + ".crt");
        Files.write(certPath, "sentinel".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> KeyClient.fetch("127.0.0.1", port, "half", prefix,
                Duration.ZERO, false, 10_000));
        assertFalse(Files.exists(Path.of(prefix + ".key")),
                "newly written key must be removed when the certificate cannot be saved");
        assertEquals("sentinel", Files.readString(certPath, StandardCharsets.US_ASCII));
    }

    @Test
    void invalidArgumentsAreRejectedBeforeConnecting() {
        Path prefix = directory.resolve("x");
        assertThrows(Exception.class, () -> KeyClient.fetch("127.0.0.1", 0, "x", prefix,
                Duration.ZERO, false, 1_000));
        assertThrows(Exception.class, () -> KeyClient.fetch("127.0.0.1", 9999, "", prefix,
                Duration.ZERO, false, 1_000));
        assertThrows(Exception.class, () -> KeyClient.fetch("127.0.0.1", 9999, "x", prefix,
                Duration.ofSeconds(-1), false, 1_000));
    }
}
