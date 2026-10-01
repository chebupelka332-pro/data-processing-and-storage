package taskj2.app;

import java.io.PrintStream;

import taskj2.config.Config;
import taskj2.config.HelpRequested;
import taskj2.config.Mode;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int status = execute(args, System.in, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int execute(String[] args, java.io.InputStream stdin, PrintStream out, PrintStream err) {
        Config cfg;
        try {
            cfg = Config.parse(args);
        } catch (HelpRequested h) {
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            Config.usage(err);
            return 2;
        }

        long inner = cfg.innerMs();
        long between = cfg.betweenMs();
        out.printf("Mode=%s threads=%d inner=%dms between=%dms%n",
                cfg.mode, cfg.threads, inner, between);
        out.println("Input: text line -> add to head; empty line -> print; exit/quit/EOF -> stop.");

        if (cfg.mode == Mode.CUSTOM) {
            return CustomListApp.run(cfg, inner, between, stdin, out, err);
        } else {
            return SyncListApp.run(cfg, inner, between, stdin, out, err);
        }
    }
}
