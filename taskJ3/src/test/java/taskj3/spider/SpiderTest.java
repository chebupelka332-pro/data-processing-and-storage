package taskj3.spider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Интеграционные тесты паука против локального stub-сервера
 */
class SpiderTest {

    private HttpServer server;
    private final Map<String, StubPage> pages = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            StubPage p = pages.get(path);
            if (p == null) {
                byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            if (p.getDelayMs() > 0) {
                try {
                    Thread.sleep(p.getDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (p.getStatus() != 200) {
                byte[] body = "error".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(p.getStatus(), body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"message\": \"").append(escape(p.getMessage())).append("\", \"successors\": [");
            for (int i = 0; i < p.getSuccessors().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(escape(p.getSuccessors().get(i))).append('"');
            }
            sb.append("]}");
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private Spider spiderWithShortTimeouts() {
        return new Spider(baseUri(),
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                Duration.ofSeconds(10), 3, 50, Duration.ofSeconds(30));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test
    void crawlsGraphWithCycleAndSorts() throws Exception {
        pages.put("/", new StubPage("root", List.of("/a", "/b"), 0, 200));
        pages.put("/a", new StubPage("alpha", List.of("/c"), 0, 200));
        pages.put("/b", new StubPage("beta", List.of("/c", "/d"), 0, 200));
        pages.put("/c", new StubPage("gamma", List.of("/a"), 0, 200)); // цикл a->c->a
        pages.put("/d", new StubPage("delta", List.of(), 0, 200));

        List<String> res = spiderWithShortTimeouts().crawl();
        assertEquals(List.of("alpha", "beta", "delta", "gamma", "root"), res);
    }

    @Test
    void visitsEachUrlOnceDespiteDiamonds() throws Exception {
        pages.put("/", new StubPage("m0", List.of("/x", "/y", "/z"), 0, 200));
        pages.put("/x", new StubPage("m1", List.of("/shared"), 0, 200));
        pages.put("/y", new StubPage("m2", List.of("/shared"), 0, 200));
        pages.put("/z", new StubPage("m3", List.of("/shared"), 0, 200));
        pages.put("/shared", new StubPage("m4", List.of(), 0, 200));

        List<String> res = spiderWithShortTimeouts().crawl();
        assertEquals(5, res.size());
        assertEquals(List.of("m0", "m1", "m2", "m3", "m4"), res);
    }

    @Test
    void parallelFetchIsFasterThanSequentialSum() throws Exception {
        pages.put("/", new StubPage("root", List.of("/l1", "/l2", "/l3", "/l4"), 0, 200));
        pages.put("/l1", new StubPage("m1", List.of(), 1000, 200));
        pages.put("/l2", new StubPage("m2", List.of(), 1000, 200));
        pages.put("/l3", new StubPage("m3", List.of(), 1000, 200));
        pages.put("/l4", new StubPage("m4", List.of(), 1000, 200));

        long t0 = System.nanoTime();
        List<String> res = spiderWithShortTimeouts().crawl();
        double secs = (System.nanoTime() - t0) / 1e9;

        assertEquals(5, res.size());
        assertTrue(secs < 3.5, "crawl took " + secs + "s, expected parallel speedup");
    }

    @Test
    void tolerates404Leaf() throws Exception {
        pages.put("/", new StubPage("root", List.of("/ok", "/missing"), 0, 200));
        pages.put("/ok", new StubPage("ok-msg", List.of(), 0, 200));

        List<String> res = spiderWithShortTimeouts().crawl();
        assertEquals(List.of("ok-msg", "root"), res);
    }
}
