package taskj1;

import taskj1.cli.Arguments;
import taskj1.client.KeyClient;
import taskj1.crypto.RsaKeyMaterialGenerator;
import taskj1.crypto.SigningKeys;
import taskj1.server.KeyServer;

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        int status = execute(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || (args.length == 1 && (args[0].equals("--help") || args[0].equals("help")))
                || (args.length == 2 && Set.of("server", "client", "init-issuer").contains(args[0])
                && args[1].equals("--help"))) {
            usage(out);
            return 0;
        }
        try {
            switch (args[0]) {
                case "server" -> server(args, out);
                case "client" -> client(args, out);
                case "init-issuer" -> initIssuer(args, out);
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
            return 1;
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static void server(String[] args, PrintStream out) throws Exception {
        Arguments options = new Arguments(args,
                Set.of("--bind", "--port", "--workers", "--signing-key", "--issuer"), Set.of());
        int port = options.integer("--port", 9000, 1, 65535);
        int workers = options.integer("--workers", Runtime.getRuntime().availableProcessors(), 1, Integer.MAX_VALUE);
        var key = SigningKeys.loadPrivate(Path.of(options.required("--signing-key")));
        var generator = new RsaKeyMaterialGenerator(key, options.required("--issuer"));
        String bind = options.value("--bind", "0.0.0.0");
        try (KeyServer server = KeyServer.start(new InetSocketAddress(bind, port), workers, generator)) {
            Thread shutdown = new Thread(server::close, "taskj1-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                out.printf("Listening on %s:%d; generation workers: %d%n", bind, server.port(), workers);
                server.awaitTermination();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    private static void client(String[] args, PrintStream out) throws Exception {
        Arguments options = new Arguments(args,
                Set.of("--host", "--port", "--name", "--out-prefix", "--delay-seconds", "--timeout-seconds"),
                Set.of("--abort-after-send"));
        String name = options.required("--name");
        String host = options.required("--host");
        int port = options.integer("--port", 9000, 1, 65535);
        int delay = options.integer("--delay-seconds", 0, 0, Integer.MAX_VALUE);
        int timeout = options.integer("--timeout-seconds", 0, 0, Integer.MAX_VALUE / 1000);
        Path prefix = Path.of(options.value("--out-prefix", "identity"));
        boolean saved = KeyClient.fetch(host, port, name, prefix, Duration.ofSeconds(delay),
                options.flag("--abort-after-send"), timeout * 1000);
        if (saved) {
            out.println("Saved " + prefix + ".key and " + prefix + ".crt");
        } else {
            out.println("Request sent; disconnected without reading the response");
        }
    }

    private static void initIssuer(String[] args, PrintStream out) throws Exception {
        Arguments options = new Arguments(args, Set.of("--private", "--public"), Set.of());
        Path privatePath = Path.of(options.value("--private", "issuer.key"));
        Path publicPath = Path.of(options.value("--public", "issuer.pub"));
        SigningKeys.generate(privatePath, publicPath);
        out.println("Created issuer RSA-4096 key: " + privatePath + "; public key: " + publicPath);
    }

    private static void usage(PrintStream out) {
        out.println("Task J1 - RSA-8192 key generation service (Java 21)");
    }
}
