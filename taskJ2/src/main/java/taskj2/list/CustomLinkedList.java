package taskj2.list;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import taskj2.util.Sleeper;

public final class CustomLinkedList implements Iterable<String> {

    private Node head;
    private final ReentrantLock headLock = new ReentrantLock();

    public void addFirst(String value) {
        headLock.lock();
        try {
            Node n = new Node(value);
            n.next = head;
            head = n;
        } finally {
            headLock.unlock();
        }
    }

    public List<String> snapshot() {
        while (true) {
            List<String> out = new ArrayList<>();
            Node curr;
            boolean firstLocked = false;
            if (!headLock.tryLock()) {
                backoff();
                continue;
            }
            try {
                curr = head;
                if (curr == null) {
                    return out;
                }
                if (curr.lock.tryLock()) {
                    firstLocked = true;
                }
            } finally {
                headLock.unlock();
            }
            if (!firstLocked) {
                backoff();
                continue;
            }
            boolean ok = true;
            while (curr != null) {
                out.add(curr.value);
                Node nxt = curr.next;
                if (nxt != null) {
                    if (!nxt.lock.tryLock()) {
                        curr.lock.unlock();
                        ok = false;
                        break;
                    }
                }
                curr.lock.unlock();
                curr = nxt;
            }
            if (!ok) {
                backoff();
                continue;
            }
            return out;
        }
    }

    @Override
    public Iterator<String> iterator() {
        return snapshot().iterator();
    }

    public int size() {
        return snapshot().size();
    }

    public boolean isEmpty() {
        headLock.lock();
        try {
            return head == null;
        } finally {
            headLock.unlock();
        }
    }

    public int bubblePass(AtomicLong stepCounter, long innerDelayMs, long betweenDelayMs)
            throws InterruptedException {
        Node start;
        headLock.lock();
        try {
            start = head;
        } finally {
            headLock.unlock();
        }
        if (start == null) {
            return 0;
        }
        Node firstNext = start.next;
        if (firstNext == null) {
            return 0;
        }

        int steps = 0;
        Node prev = null;
        Node curr = start;
        Node next = firstNext;
        while (curr != null && next != null) {
            StepOutcome outcome = stepTriple(prev, curr, next, innerDelayMs, stepCounter);
            steps++;
            Sleeper.sleepInterruptibly(betweenDelayMs);
            if (outcome.reachedEnd) {
                break;
            }
            prev = outcome.nextPrev;
            curr = outcome.nextCurr;
            next = outcome.nextNext;
        }
        return steps;
    }

    private StepOutcome stepTriple(Node prev, Node curr, Node next,
                                   long innerDelayMs, AtomicLong stepCounter)
            throws InterruptedException {
        if (curr == null || next == null) {
            return new StepOutcome(true, null, null, null);
        }
        boolean useHead = (prev == null);
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            boolean headAcquired = false;
            boolean prevAcquired = false;
            if (useHead) {
                if (!headLock.tryLock()) {
                    Sleeper.sleepInterruptibly(1);
                    continue;
                }
                headAcquired = true;
            }
            if (prev != null) {
                if (!prev.lock.tryLock()) {
                    if (headAcquired) {
                        headLock.unlock();
                    }
                    Sleeper.sleepInterruptibly(1);
                    continue;
                }
                prevAcquired = true;
            }
            if (!curr.lock.tryLock()) {
                if (prevAcquired) {
                    prev.lock.unlock();
                }
                if (headAcquired) {
                    headLock.unlock();
                }
                Sleeper.sleepInterruptibly(1);
                continue;
            }
            boolean currAcquired = true;
            if (!next.lock.tryLock()) {
                curr.lock.unlock();
                if (prevAcquired) {
                    prev.lock.unlock();
                }
                if (headAcquired) {
                    headLock.unlock();
                }
                Sleeper.sleepInterruptibly(1);
                continue;
            }
            boolean nextAcquired = true;
            try {
                boolean valid;
                Node following;
                if (prev == null) {
                    valid = (head == curr && curr.next == next);
                    following = next.next;
                } else {
                    valid = (prev.next == curr && curr.next == next);
                    following = next.next;
                }

                if (valid) {
                    Sleeper.sleepInterruptibly(innerDelayMs / 2);
                    boolean needSwap = curr.value.compareTo(next.value) > 0;
                    if (needSwap) {
                        next.next = curr;
                        curr.next = following;
                        if (prev == null) {
                            head = next;
                        } else {
                            prev.next = next;
                        }
                    }
                    Sleeper.sleepInterruptibly(innerDelayMs - innerDelayMs / 2);
                    stepCounter.incrementAndGet();

                    Node newPrev = needSwap ? next : curr;
                    Node newCurr = needSwap ? curr : next;
                    Node newNext = following;
                    if (newNext == null) {
                        return new StepOutcome(true, null, null, null);
                    }
                    return new StepOutcome(false, newPrev, newCurr, newNext);
                } else {
                    stepCounter.incrementAndGet();
                    Node newNext = following;
                    if (newNext == null) {
                        return new StepOutcome(true, null, null, null);
                    }
                    return new StepOutcome(false, curr, next, newNext);
                }
            } finally {
                if (nextAcquired) {
                    next.lock.unlock();
                }
                if (currAcquired) {
                    curr.lock.unlock();
                }
                if (prevAcquired) {
                    prev.lock.unlock();
                }
                if (headAcquired) {
                    headLock.unlock();
                }
            }
        }
    }

    private static void backoff() {
        Thread.yield();
        LockSupport.parkNanos(100_000);
    }
}
