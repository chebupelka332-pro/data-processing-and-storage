package taskj1.server;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import taskj1.client.KeyClient;
import taskj1.crypto.KeyMaterial;
import taskj1.crypto.KeyMaterialGenerator;
import taskj1.crypto.PemFiles;
import taskj1.crypto.RsaKeyMaterialGenerator;
import taskj1.crypto.SigningKeys;
import taskj1.protocol.Protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(120)
class KeyServerTest {
    @TempDir
    Path directory;

    private static final class FakeGenerator implements KeyMaterialGenerator {
        final CountDownLatch gate;
        final long workMillis;
        final Set<String> failNames = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final byte[] keyDer = new byte[]{1, 2, 3, 4};
        final byte[] certDer = new byte[]{5, 6, 7};

        FakeGenerator(CountDownLatch gate, long workMillis) {
            this.gate = gate;
            this.workMillis = workMillis;
        }

        @Override
        public KeyMaterial generate(String name) throws Exception {
            calls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                if (gate != null) {
                    assertTrue(gate.await(60, TimeUnit.SECONDS), "generation gate timed out");
                }
                if (workMillis > 0) {
                    Thread.sleep(workMillis);
                }
                if (failNames.contains(name)) {
                    throw new IOException("simulated generation failure");
                }
                return new KeyMaterial(keyDer, certDer);
            } finally {
                active.decrementAndGet();
            }
        }

        int callsFor(String name) {
            AtomicInteger count = calls.get(name);
            return count == null ? 0 : count.get();
        }

        boolean awaitCalls(String name, int expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (callsFor(name) >= expected) {
                    return true;
                }
                Thread.sleep(10);
            }
            return callsFor(name) >= expected;
        }
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    private static KeyMaterial request(int port, String name) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(Protocol.request(name));
            out.flush();
            return Protocol.readResponse(socket.getInputStream());
        }
    }

    @Test
    void concurrentRequestsForSameNameShareOneGeneration() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeGenerator generator = new FakeGenerator(gate, 0);
        try (KeyServer server = KeyServer.start(loopback(), 4, generator)) {
            int port = server.port();
            int clients = 10;
            ExecutorService pool = Executors.newFixedThreadPool(clients);
            try {
                List<Future<KeyMaterial>> futures = new ArrayList<>();
                for (int i = 0; i < clients; i++) {
                    futures.add(pool.submit(() -> request(port, "alice")));
                }
                assertTrue(generator.awaitCalls("alice", 1, Duration.ofSeconds(15)),
                        "generation never started");
                Thread.sleep(500);
                gate.countDown();
                for (Future<KeyMaterial> future : futures) {
                    KeyMaterial material = future.get(30, TimeUnit.SECONDS);
                    assertArrayEquals(generator.keyDer, material.privateKeyDer());
                    assertArrayEquals(generator.certDer, material.certificateDer());
                }
                assertEquals(1, generator.callsFor("alice"),
                        "same name must be generated exactly once");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void repeatRequestIsServedFromCache() throws Exception {
        FakeGenerator generator = new FakeGenerator(null, 20);
        try (KeyServer server = KeyServer.start(loopback(), 2, generator)) {
            int port = server.port();
            KeyMaterial first = request(port, "alice");
            KeyMaterial second = request(port, "alice");
            assertArrayEquals(first.privateKeyDer(), second.privateKeyDer());
            assertArrayEquals(first.certificateDer(), second.certificateDer());
            assertEquals(1, generator.callsFor("alice"));
        }
    }

    @Test
    void distinctNamesRunInParallelWithinWorkerLimit() throws Exception {
        FakeGenerator generator = new FakeGenerator(null, 300);
        try (KeyServer server = KeyServer.start(loopback(), 2, generator)) {
            int port = server.port();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<KeyMaterial>> futures = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    String name = "user-" + i;
                    futures.add(pool.submit(() -> request(port, name)));
                }
                for (Future<KeyMaterial> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            for (int i = 0; i < 4; i++) {
                assertEquals(1, generator.callsFor("user-" + i));
            }
            assertTrue(generator.maxActive.get() <= 2,
                    "active generations exceeded worker count: " + generator.maxActive.get());
            assertEquals(2, generator.maxActive.get(), "workers did not run in parallel");
        }
    }

    @Test
    void abortedClientDoesNotCancelGeneration() throws Exception {
        FakeGenerator generator = new FakeGenerator(null, 200);
        try (KeyServer server = KeyServer.start(loopback(), 2, generator)) {
            int port = server.port();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                OutputStream out = socket.getOutputStream();
                out.write(Protocol.request("bob"));
                out.flush();
            }
            assertTrue(generator.awaitCalls("bob", 1, Duration.ofSeconds(15)));
            KeyMaterial material = request(port, "bob");
            assertArrayEquals(generator.keyDer, material.privateKeyDer());
            assertEquals(1, generator.callsFor("bob"),
                    "aborted request must still populate the cache exactly once");
        }
    }

    @Test
    void generationFailureReleasesNameForRetry() throws Exception {
        FakeGenerator generator = new FakeGenerator(null, 10);
        generator.failNames.add("bad");
        try (KeyServer server = KeyServer.start(loopback(), 2, generator)) {
            int port = server.port();
            IOException failure = assertThrows(IOException.class, () -> request(port, "bad"));
            assertTrue(failure.getMessage().contains("Server error"), failure.getMessage());
            assertEquals(1, generator.callsFor("bad"));

            generator.failNames.remove("bad");
            KeyMaterial material = request(port, "bad");
            assertArrayEquals(generator.keyDer, material.privateKeyDer());
            assertEquals(2, generator.callsFor("bad"));
        }
    }

    @Test
    void fragmentedRequestIsAssembled() throws Exception {
        FakeGenerator generator = new FakeGenerator(null, 10);
        try (KeyServer server = KeyServer.start(loopback(), 2, generator)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
                socket.setSoTimeout(30_000);
                OutputStream out = socket.getOutputStream();
                out.write("ali".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(200);
                out.write(new byte[]{'c', 'e', 0});
                out.flush();
                KeyMaterial material = Protocol.readResponse(socket.getInputStream());
                assertArrayEquals(generator.keyDer, material.privateKeyDer());
            }
            assertEquals(1, generator.callsFor("alice"));
        }
    }

    @Test
    void invalidNamesGetErrorsWithoutKillingTheServer() throws Exception {
        FakeGenerator generator = new FakeGenerator(null, 10);
        try (KeyServer server = KeyServer.start(loopback(), 2, generator)) {
            int port = server.port();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                socket.setSoTimeout(30_000);
                socket.getOutputStream().write(new byte[]{0});
                socket.getOutputStream().flush();
                IOException failure = assertThrows(IOException.class,
                        () -> Protocol.readResponse(socket.getInputStream()));
                assertTrue(failure.getMessage().contains("Server error"), failure.getMessage());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                socket.setSoTimeout(30_000);
                socket.getOutputStream().write(new byte[]{(byte) 0xFF, 0});
                socket.getOutputStream().flush();
                assertThrows(IOException.class,
                        () -> Protocol.readResponse(socket.getInputStream()));
            }
            assertArrayEquals(generator.keyDer, request(port, "alice").privateKeyDer());
        }
    }

    @Test
    void serverSurvivesConnectionsThatSendNothing() throws Exception {
        FakeGenerator generator = new FakeGenerator(null, 10);
        try (KeyServer server = KeyServer.start(loopback(), 2, generator)) {
            int port = server.port();
            try (Socket ignored = new Socket()) {
                ignored.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            }
            try (Socket partial = new Socket()) {
                partial.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                partial.getOutputStream().write("unfinished".getBytes(StandardCharsets.US_ASCII));
                partial.getOutputStream().flush();
            }
            Thread.sleep(200);
            assertArrayEquals(generator.keyDer, request(port, "alice").privateKeyDer());
        }
    }

    @Test
    void manyConcurrentClientsIncludingSlowAndAbortedAreAllServed() throws Exception {
        FakeGenerator generator = new FakeGenerator(null, 10);
        try (KeyServer server = KeyServer.start(loopback(), 4, generator)) {
            int port = server.port();
            ExecutorService pool = Executors.newFixedThreadPool(64);
            try {
                List<Future<KeyMaterial>> fast = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    String name = "load-" + i;
                    fast.add(pool.submit(() -> request(port, name)));
                }
                List<Future<KeyMaterial>> slow = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    String name = "slow-" + i;
                    slow.add(pool.submit(() -> {
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                            socket.setSoTimeout(60_000);
                            OutputStream out = socket.getOutputStream();
                            out.write(Protocol.request(name));
                            out.flush();
                            Thread.sleep(500);
                            return Protocol.readResponse(socket.getInputStream());
                        }
                    }));
                }
                List<Future<?>> aborted = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    String name = "gone-" + i;
                    aborted.add(pool.submit(() -> {
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                            OutputStream out = socket.getOutputStream();
                            out.write(Protocol.request(name));
                            out.flush();
                        }
                        return null;
                    }));
                }
                for (Future<KeyMaterial> future : fast) {
                    future.get(60, TimeUnit.SECONDS);
                }
                for (Future<KeyMaterial> future : slow) {
                    future.get(60, TimeUnit.SECONDS);
                }
                for (Future<?> future : aborted) {
                    future.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void largeResponsesReachSlowReadersIntact() throws Exception {
        byte[] bigKey = new byte[Protocol.MAX_PRIVATE_KEY_BYTES];
        byte[] bigCert = new byte[Protocol.MAX_CERTIFICATE_BYTES];
        for (int i = 0; i < bigKey.length; i++) {
            bigKey[i] = (byte) (i & 0x7F);
        }
        for (int i = 0; i < bigCert.length; i++) {
            bigCert[i] = (byte) ((i + 1) & 0x7F);
        }
        KeyMaterialGenerator sized = name -> new KeyMaterial(bigKey, bigCert);
        try (KeyServer server = KeyServer.start(loopback(), 2, sized)) {
            int port = server.port();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                socket.setSoTimeout(60_000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                out.write(Protocol.request("big"));
                out.flush();
                KeyMaterial material = Protocol.readResponse(new SlowInputStream(in));
                assertArrayEquals(bigKey, material.privateKeyDer());
                assertArrayEquals(bigCert, material.certificateDer());
            }
        }
    }

    private static final class SlowInputStream extends InputStream {
        private final InputStream delegate;

        SlowInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, Math.min(length, 1));
        }
    }

    @Test
    @Tag("integration")
    @Timeout(600)
    void realRsa8192RoundTripOverNetwork() throws Exception {
        KeyPairGenerator issuerGen = KeyPairGenerator.getInstance("RSA");
        issuerGen.initialize(2048);
        var issuerPair = issuerGen.generateKeyPair();
        Path issuerPath = directory.resolve("issuer.key");
        PemFiles.writeNew(issuerPath, "PRIVATE KEY", issuerPair.getPrivate().getEncoded());
        PrivateKey signingKey = SigningKeys.loadPrivate(issuerPath);
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) signingKey;
        PublicKey issuerPublic = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));

        var generator = new RsaKeyMaterialGenerator(signingKey, "CN=TaskJ1 Test Issuer");
        try (KeyServer server = KeyServer.start(loopback(), 2, generator)) {
            Path prefix = directory.resolve("alice");
            assertTrue(KeyClient.fetch("127.0.0.1", server.port(), "alice", prefix,
                    Duration.ZERO, false, 0));

            PrivateKey clientPrivate = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(pemBody(prefix.resolveSibling("alice.key"), "PRIVATE KEY")));
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(
                            pemBody(prefix.resolveSibling("alice.crt"), "CERTIFICATE")));
            assertEquals(8192, ((RSAKey) clientPrivate).getModulus().bitLength());
            assertEquals(8192, ((RSAKey) certificate.getPublicKey()).getModulus().bitLength());
            assertEquals(((RSAKey) clientPrivate).getModulus(),
                    ((RSAKey) certificate.getPublicKey()).getModulus());
            assertSignatureMatches(clientPrivate, certificate.getPublicKey());
            certificate.verify(issuerPublic);
            assertTrue(certificate.getSubjectX500Principal().getName().contains("CN=alice"),
                    certificate.getSubjectX500Principal().getName());

            Path second = directory.resolve("alice-again");
            assertTrue(KeyClient.fetch("127.0.0.1", server.port(), "alice", second,
                    Duration.ZERO, false, 0));
            assertArrayEquals(Files.readAllBytes(Path.of(second + ".key")),
                    Files.readAllBytes(Path.of(prefix + ".key")));
        }
    }

    private static byte[] pemBody(Path path, String type) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
        assertEquals("-----BEGIN " + type + "-----", lines.getFirst());
        assertEquals("-----END " + type + "-----", lines.getLast());
        String encoded = String.join("", lines.subList(1, lines.size() - 1));
        return Base64.getMimeDecoder().decode(encoded);
    }

    private static void assertSignatureMatches(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        byte[] message = "taskj1 server round-trip".getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message);
        byte[] signed = signature.sign();
        signature.initVerify(publicKey);
        signature.update(message);
        assertTrue(signature.verify(signed));
    }
}
