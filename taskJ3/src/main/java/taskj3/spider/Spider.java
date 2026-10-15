package taskj3.spider;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Spider {

    private final URI baseUri;
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final long backoffMs;
    private final Duration globalTimeout;

    public Spider(URI baseUri) {
        this(baseUri,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                Duration.ofSeconds(20),
                3,
                500,
                Duration.ofSeconds(115));
    }

    public Spider(URI baseUri,
                  HttpClient client,
                  Duration requestTimeout,
                  int maxAttempts,
                  long backoffMs,
                  Duration globalTimeout) {
        if (baseUri == null) {
            throw new IllegalArgumentException("baseUri is null");
        }
        String s = baseUri.toString();
        if (!s.endsWith("/")) {
            s = s + "/";
        }
        this.baseUri = URI.create(s);
        this.client = client == null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                : client;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(20) : requestTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(0, backoffMs);
        this.globalTimeout = globalTimeout == null ? Duration.ofSeconds(115) : globalTimeout;
    }

    public List<String> crawl() throws InterruptedException {
        Set<String> visited = ConcurrentHashMap.newKeySet();
        Queue<String> messages = new ConcurrentLinkedQueue<>();
        AtomicInteger pending = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(1);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            CrawlState state = new CrawlState(visited, messages, pending, done, exec);
            visited.add("/");
            pending.incrementAndGet();
            submit(state, "/");

            boolean finished = done.await(globalTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                System.err.println("Spider: global timeout " + globalTimeout
                        + " exceeded, returning partial result (visited=" + visited.size()
                        + ", messages=" + messages.size()
                        + ", pending=" + pending.get() + ")");
            }
        }

        List<String> sorted = new ArrayList<>(messages);
        Collections.sort(sorted);
        return sorted;
    }

    private void submit(CrawlState state, String path) {
        try {
            state.exec().submit(() -> fetch(path, state));
        } catch (RejectedExecutionException e) {
            System.err.println("Spider: task rejected for " + path + ": " + e);
            if (state.pending().decrementAndGet() == 0) {
                state.done().countDown();
            }
        }
    }

    private void fetch(String path, CrawlState state) {
        try {
            Page page = fetchWithRetry(path);
            if (page != null) {
                state.messages().add(page.getMessage());
                for (String succ : page.getSuccessors()) {
                    String norm = normalize(succ);
                    if (norm == null) {
                        continue;
                    }
                    if (state.visited().add(norm)) {
                        state.pending().incrementAndGet();
                        submit(state, norm);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("Spider: unexpected error on " + path + ": " + t);
        } finally {
            if (state.pending().decrementAndGet() == 0) {
                state.done().countDown();
            }
        }
    }

    private Page fetchWithRetry(String path) {
        URI uri;
        try {
            uri = resolve(path);
        } catch (IllegalArgumentException e) {
            System.err.println("Spider: bad path '" + path + "': " + e.getMessage());
            return null;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(requestTimeout)
                        .GET()
                        .header("Accept", "application/json")
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) {
                    try {
                        return JsonParser.parsePage(resp.body());
                    } catch (IllegalArgumentException e) {
                        System.err.println("Spider: bad JSON from " + uri + ": " + e.getMessage());
                    }
                } else if (code == 404) {
                    System.err.println("Spider: 404 for " + uri + " (treated as leaf)");
                    return null;
                } else if (code == 429 || code >= 500) {
                    System.err.println("Spider: retryable status " + code + " for " + uri
                            + " (attempt " + attempt + "/" + maxAttempts + ")");
                } else {
                    System.err.println("Spider: status " + code + " for " + uri + " (treated as leaf)");
                    return null;
                }
            } catch (HttpTimeoutException e) {
                System.err.println("Spider: timeout " + uri
                        + " (attempt " + attempt + "/" + maxAttempts + ")");
            } catch (ConnectException e) {
                System.err.println("Spider: connect error " + uri + ": " + e
                        + " (attempt " + attempt + "/" + maxAttempts + ")");
            } catch (IOException e) {
                System.err.println("Spider: io error " + uri + ": " + e
                        + " (attempt " + attempt + "/" + maxAttempts + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Spider: interrupted on " + uri);
                return null;
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(backoffMs * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        System.err.println("Spider: giving up " + uri + " after " + maxAttempts + " attempts");
        return null;
    }

    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("http://") || t.startsWith("https://")) {
            try {
                URI u = URI.create(t);
                String p = u.getRawPath();
                String q = u.getRawQuery();
                if (p == null || p.isEmpty()) {
                    p = "/";
                }
                t = q == null ? p : p + "?" + q;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (!t.startsWith("/")) {
            t = "/" + t;
        }
        return t;
    }

    private URI resolve(String path) {
        String norm = normalize(path);
        if (norm == null) {
            throw new IllegalArgumentException("empty path");
        }
        String base = baseUri.toString();
        String withoutSlash = norm.startsWith("/") ? norm.substring(1) : norm;
        return URI.create(base + withoutSlash);
    }
}
