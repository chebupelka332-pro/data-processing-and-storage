package taskj2.sort;

import java.util.concurrent.atomic.AtomicLong;

import taskj2.list.CustomLinkedList;

public final class SortWorker implements Runnable {

    private final CustomLinkedList list;
    private final AtomicLong steps = new AtomicLong();
    private final long innerDelayMs;
    private final long betweenDelayMs;

    public SortWorker(CustomLinkedList list, long innerDelayMs, long betweenDelayMs) {
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
                list.bubblePass(steps, innerDelayMs, betweenDelayMs);

                if (list.isEmpty() || list.size() <= 1) {
                    Thread.sleep(Math.max(betweenDelayMs, 1));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
