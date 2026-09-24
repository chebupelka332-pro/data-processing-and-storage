package taskj1.client;

import taskj1.crypto.KeyMaterial;
import taskj1.crypto.PemFiles;
import taskj1.protocol.Protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class KeyClient {
    private KeyClient() {
    }

    public static boolean fetch(String host, int port, String name, Path outputPrefix,
                                Duration delay, boolean abortAfterSend, int readTimeoutMillis)
            throws IOException, InterruptedException {
        byte[] request = Protocol.request(name);
        if (port < 1 || port > 65535 || readTimeoutMillis < 0 || delay.isNegative()) {
            throw new IllegalArgumentException("Invalid port, timeout or delay");
        }
        KeyMaterial material;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout(readTimeoutMillis);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            if (abortAfterSend) {
                return false;
            }
            Thread.sleep(delay);
            material = Protocol.readResponse(socket.getInputStream());
        }

        Path keyPath = Path.of(outputPrefix + ".key");
        Path certificatePath = Path.of(outputPrefix + ".crt");
        PemFiles.writeNew(keyPath, "PRIVATE KEY", material.privateKeyDer());
        try {
            PemFiles.writeNew(certificatePath, "CERTIFICATE", material.certificateDer());
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(keyPath);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return true;
    }
}
