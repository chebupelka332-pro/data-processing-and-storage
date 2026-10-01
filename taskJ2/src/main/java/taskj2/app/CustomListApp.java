package taskj2.app;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

import taskj2.config.Config;
import taskj2.list.CustomLinkedList;
import taskj2.sort.SortWorker;
import taskj2.util.Splitter;

final class CustomListApp {

    private CustomListApp() {
    }

    static int run(Config cfg, long inner, long between,
                   InputStream stdin, PrintStream out, PrintStream err) {
        CustomLinkedList list = new CustomLinkedList();
        SortWorker[] workers = new SortWorker[cfg.threads];
        Thread[] threads = new Thread[cfg.threads];
        for (int i = 0; i < cfg.threads; i++) {
            workers[i] = new SortWorker(list, inner, between);
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
        printStats(workers, startNanos, inner, between, out);
        return 0;
    }

    private static void inputLoop(CustomLinkedList list, InputStream stdin, PrintStream out) {
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
                for (int i = parts.size() - 1; i >= 0; i--) {
                    list.addFirst(parts.get(i));
                }
            }
        }
    }

    private static void print(CustomLinkedList list, PrintStream out) {
        int n = 0;
        out.println("--- list begin ---");
        for (String s : list) {
            out.printf("[%d] %s%n", n++, s);
        }
        out.printf("--- list end (%d items) ---%n", n);
    }

    private static void fillReverse(CustomLinkedList list, int k) {
        for (int i = 0; i < k; i++) {
            list.addFirst(String.format("item-%04d", i));
        }
    }

    private static void printStats(SortWorker[] workers, long startNanos,
                                   long inner, long between, PrintStream out) {
        long total = 0;
        for (int i = 0; i < workers.length; i++) {
            long s = workers[i].steps();
            total += s;
            out.printf("worker sorter-%d steps=%d%n", i, s);
        }
        double elapsedSec = (System.nanoTime() - startNanos) / 1e9;
        out.printf("TOTAL steps=%d (mode=custom threads=%d elapsed=%.1fs)%n",
                total, workers.length, elapsedSec);
    }
}
