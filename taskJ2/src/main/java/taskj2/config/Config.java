package taskj2.config;

public final class Config {

    public int threads = 2;
    public long delayMs = 1000;
    public Long innerMsOverride;
    public Long betweenMsOverride;
    public Mode mode = Mode.CUSTOM;
    public boolean benchmark;
    public int benchmarkItems = 20;
    public int benchmarkSeconds = 5;

    public long innerMs() {
        return innerMsOverride != null ? innerMsOverride : delayMs;
    }

    public long betweenMs() {
        return betweenMsOverride != null ? betweenMsOverride : delayMs;
    }

    public static Config parse(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--threads" -> c.threads = integer(args, ++i, "--threads", 1, 64);
                case "--delay-ms" -> c.delayMs = longArg(args, ++i, "--delay-ms", 0, 60_000);
                case "--inner-ms" -> c.innerMsOverride = longArg(args, ++i, "--inner-ms", 0, 60_000);
                case "--between-ms" -> c.betweenMsOverride = longArg(args, ++i, "--between-ms", 0, 60_000);
                case "--mode" -> {
                    String v = value(args, ++i, "--mode");
                    c.mode = switch (v.toLowerCase()) {
                        case "custom" -> Mode.CUSTOM;
                        case "synclist", "sync", "arraylist" -> Mode.SYNCLIST;
                        default -> throw new IllegalArgumentException(
                                "Unknown --mode: " + v + " (expected custom|synclist)");
                    };
                }
                case "--benchmark" -> c.benchmark = true;
                case "--benchmark-items" -> c.benchmarkItems = integer(args, ++i, "--benchmark-items", 1, 100_000);
                case "--benchmark-seconds" ->
                        c.benchmarkSeconds = integer(args, ++i, "--benchmark-seconds", 1, 3600);
                case "--help", "-h" -> {
                    usage(System.out);
                    throw new HelpRequested();
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        return c;
    }

    private static String value(String[] args, int i, String name) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + name);
        }
        return args[i];
    }

    private static int integer(String[] args, int i, String name, int min, int max) {
        int v;
        try {
            v = Integer.parseInt(value(args, i, name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad integer for " + name + ": " + args[i]);
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]");
        }
        return v;
    }

    private static long longArg(String[] args, int i, String name, long min, long max) {
        long v;
        try {
            v = Long.parseLong(value(args, i, name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad integer for " + name + ": " + args[i]);
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]");
        }
        return v;
    }

    public static void usage(java.io.PrintStream out) {
        out.println("Usage: taskj2 [--threads N] [--delay-ms M] [--inner-ms M] [--between-ms M]");
        out.println("              [--mode custom|synclist] [--benchmark [--benchmark-items K --benchmark-seconds T]]");
        out.println("Interactive stdin: non-empty line -> add to head (split by 80 chars);");
        out.println("  empty line -> print list; 'exit'/'quit' or EOF -> stop and print step stats.");
    }
}
