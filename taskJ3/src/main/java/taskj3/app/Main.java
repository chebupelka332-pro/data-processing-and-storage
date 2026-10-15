package taskj3.app;

import picocli.CommandLine;

/** Точка входа паука: делегирует разбор аргументов Picocli. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SpiderCommand()).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
