package taskj2.app;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

import taskj2.config.Config;
import taskj2.sort.SyncListSorter;
import taskj2.util.Splitter;

final class SyncListApp {

    private SyncListApp() {
    }

    static int run(Config cfg, long inner, long between,
                   InputStream stdin, PrintStream out, PrintStream err) {
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        SyncListSorter[] workers = new SyncListSorter[cfg.threads];
        Thread[] threads = new Thread[cfg.threads];
        for (int i = 0; i < cfg.threads; i++) {
            workers[i] = new SyncListSorter(list, inner, between);
            threads[i] = new Thread(workers[i], "sorter-" + i);
        }
        long startNanos = System.nanoTime();
        for (Thread t : threads) {
            t.start();
        }
        try {
            if (cfg.benchmark) {
                fillReverse(list, cfg.benchmarkItems);
                out.printf("Benchmark: items=%d, duration=%ds ...%n",
                        cfg.benchmarkItems, cfg.benchmarkSeconds);
                Thread.sleep(cfg.benchmarkSeconds * 1000L);
            } else {
                inputLoop(list, stdin, out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
        } finally {
            for (Thread t : threads) {
                t.interrupt();
            }
            for (Thread t : threads) {
                try {
                    t.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long total = 0;
        for (int i = 0; i < workers.length; i++) {
            long s = workers[i].steps();
            total += s;
            out.printf("worker sorter-%d steps=%d%n", i, s);
        }
        double elapsedSec = (System.nanoTime() - startNanos) / 1e9;
        out.printf("TOTAL steps=%d (mode=synclist threads=%d elapsed=%.1fs)%n",
                total, workers.length, elapsedSec);
        out.printf("Theory: custom ~ N*T/(inner+between), synclist ~ T/(inner+between).%n");
        if (inner + between > 0) {
            out.printf("Theory estimate for this run: custom ~ %.0f, synclist ~ %.0f%n",
                    workers.length * elapsedSec * 1000.0 / (inner + between),
                    elapsedSec * 1000.0 / (inner + between));
        }
        return 0;
    }

    private static void inputLoop(List<String> list, InputStream stdin, PrintStream out) {
        Scanner sc = new Scanner(stdin, StandardCharsets.UTF_8);
        while (true) {
            if (!sc.hasNextLine()) {
                out.println("[EOF]");
                break;
            }
            String line = sc.nextLine();
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                break;
            }
            if (line.isEmpty()) {
                print(list, out);
            } else {
                List<String> parts = Splitter.split80(line);
                synchronized (list) {
                    for (int i = parts.size() - 1; i >= 0; i--) {
                        list.add(0, parts.get(i));
                    }
                }
            }
        }
    }

    private static void print(List<String> list, PrintStream out) {
        synchronized (list) {
            out.println("--- list begin ---");
            int n = 0;
            for (String s : list) {
                out.printf("[%d] %s%n", n++, s);
            }
            out.printf("--- list end (%d items) ---%n", n);
        }
    }

    private static void fillReverse(List<String> list, int k) {
        synchronized (list) {
            for (int i = 0; i < k; i++) {
                list.add(0, String.format("item-%04d", i));
            }
        }
    }
}
