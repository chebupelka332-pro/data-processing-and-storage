package taskj2.sort;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import taskj2.util.Sleeper;

public final class SyncListSorter implements Runnable {

    private final List<String> list;
    private final AtomicLong steps = new AtomicLong();
    private final long innerDelayMs;
    private final long betweenDelayMs;

    public SyncListSorter(List<String> list, long innerDelayMs, long betweenDelayMs) {
        this.list = list;
        this.innerDelayMs = innerDelayMs;
        this.betweenDelayMs = betweenDelayMs;
    }

    public long steps() {
        return steps.get();
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int n;
                synchronized (list) {
                    n = list.size();
                }
                if (n <= 1) {
                    Thread.sleep(Math.max(betweenDelayMs, 1));
                    continue;
                }
                for (int i = 0; i + 1 < n; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    synchronized (list) {
                        if (i + 1 >= list.size()) {
                            break;
                        }
                        String a = list.get(i);
                        String b = list.get(i + 1);

                        Sleeper.sleepInterruptibly(innerDelayMs / 2);
                        if (a.compareTo(b) > 0) {
                            list.set(i, b);
                            list.set(i + 1, a);
                        }
                        Sleeper.sleepInterruptibly(innerDelayMs - innerDelayMs / 2);
                        steps.incrementAndGet();
                    }

                    Sleeper.sleepInterruptibly(betweenDelayMs);
                    synchronized (list) {
                        n = list.size();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
