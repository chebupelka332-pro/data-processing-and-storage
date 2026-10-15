package taskj3.spider;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public record CrawlState(
        Set<String> visited,
        Queue<String> messages,
        AtomicInteger pending,
        CountDownLatch done,
        ExecutorService exec) {
}
