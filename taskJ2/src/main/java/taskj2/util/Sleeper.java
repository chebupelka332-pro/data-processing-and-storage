package taskj2.util;

public final class Sleeper {

    private Sleeper() {
    }

    public static void sleepInterruptibly(long ms) throws InterruptedException {
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }
}
