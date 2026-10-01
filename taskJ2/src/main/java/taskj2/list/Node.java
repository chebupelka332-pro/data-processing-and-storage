package taskj2.list;

import java.util.concurrent.locks.ReentrantLock;

final class Node {
    String value;
    volatile Node next;
    final ReentrantLock lock = new ReentrantLock();

    Node(String value) {
        this.value = value;
    }
}
