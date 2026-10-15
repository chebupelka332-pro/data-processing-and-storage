package taskj3.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import taskj3.spider.Spider;

@Command(
        name = "spider",
        mixinStandardHelpOptions = true,
        description = "Crawl an HTTP-server graph with virtual threads and print sorted messages.")
public final class SpiderCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            paramLabel = "BASE_URL",
            description = "Base URL of the server, e.g. http://localhost:8080 (scheme defaults to http).",
            converter = BaseUriConverter.class)
    URI baseUri;

    @Option(
            names = {"-o", "--out"},
            paramLabel = "FILE",
            description = "Write sorted messages to FILE instead of stdout.")
    Path outFile;

    @Option(
            names = "--timeout",
            paramLabel = "SEC",
            defaultValue = "20",
            description = "Per-request timeout in seconds (default: ${DEFAULT-VALUE}).")
    long timeoutSec;

    @Option(
            names = "--attempts",
            paramLabel = "N",
            defaultValue = "3",
            description = "Retry attempts per request (default: ${DEFAULT-VALUE}).")
    int maxAttempts;

    @Option(
            names = "--global-timeout",
            paramLabel = "SEC",
            defaultValue = "115",
            description = "Overall crawl deadline in seconds (default: ${DEFAULT-VALUE}).")
    long globalTimeoutSec;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        if (timeoutSec < 1) {
            throw new ParameterException(spec.commandLine(), "--timeout must be >= 1");
        }
        if (maxAttempts < 1) {
            throw new ParameterException(spec.commandLine(), "--attempts must be >= 1");
        }
        if (globalTimeoutSec < 1) {
            throw new ParameterException(spec.commandLine(), "--global-timeout must be >= 1");
        }

        Duration requestTimeout = Duration.ofSeconds(timeoutSec);
        Duration globalTimeout = Duration.ofSeconds(globalTimeoutSec);

        spec.commandLine().getErr().println("Crawling " + baseUri + " with virtual threads ...");
        long t0 = System.nanoTime();
        List<String> sorted;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            Spider spider = new Spider(
                    baseUri, client, requestTimeout, maxAttempts, 500, globalTimeout);
            sorted = spider.crawl();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            spec.commandLine().getErr().println("Interrupted");
            return 130;
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        spec.commandLine().getErr().printf("Done: %d messages in %.1fs%n", sorted.size(), secs);

        if (outFile != null) {
            Files.write(outFile, sorted, StandardCharsets.UTF_8);
            spec.commandLine().getErr().println("Written to " + outFile);
        } else {
            var out = spec.commandLine().getOut();
            for (String m : sorted) {
                out.println(m);
            }
            out.flush();
        }
        return 0;
    }
}
