package taskj2.list;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import taskj2.sort.SortWorker;
import taskj2.util.Splitter;

import static org.junit.jupiter.api.Assertions.*;

class CustomLinkedListTest {

    @Test
    void addFirstPutsToHead() {
        CustomLinkedList list = new CustomLinkedList();
        list.addFirst("b");
        list.addFirst("a");
        assertEquals(List.of("a", "b"), list.snapshot());
    }

    @Test
    void split80CutsLongLines() {
        String s = "x".repeat(200);
        List<String> parts = Splitter.split80(s);
        assertEquals(3, parts.size());
        assertEquals(80, parts.get(0).length());
        assertEquals(80, parts.get(1).length());
        assertEquals(40, parts.get(2).length());
        assertEquals(s, String.join("", parts));
    }

    @Test
    void split80ShortLineUnchanged() {
        assertEquals(List.of("hello"), Splitter.split80("hello"));
        assertTrue(Splitter.split80("").isEmpty());
    }

    @Test
    void forEachSeesAllElements() {
        CustomLinkedList list = new CustomLinkedList();
        list.addFirst("c");
        list.addFirst("b");
        list.addFirst("a");
        List<String> seen = new ArrayList<>();
        for (String s : list) {
            seen.add(s);
        }
        assertEquals(List.of("a", "b", "c"), seen);
    }

    @Test
    void bubblePassSortsSingleThreaded() throws Exception {
        CustomLinkedList list = new CustomLinkedList();

        for (String v : List.of("a", "b", "c", "d", "e")) {
            list.addFirst(v);
        }
        assertEquals(List.of("e", "d", "c", "b", "a"), list.snapshot());
        AtomicLong steps = new AtomicLong();
        for (int i = 0; i < 10; i++) {
            list.bubblePass(steps, 0, 0);
        }
        assertEquals(List.of("a", "b", "c", "d", "e"), list.snapshot());
        assertTrue(steps.get() > 0);
    }

    @Test
    void bubblePassKeepsMultiset() throws Exception {
        CustomLinkedList list = new CustomLinkedList();
        List<String> input = List.of("delta", "alpha", "charlie", "bravo", "alpha");
        for (int i = input.size() - 1; i >= 0; i--) {
            list.addFirst(input.get(i));
        }
        AtomicLong steps = new AtomicLong();
        for (int i = 0; i < 10; i++) {
            list.bubblePass(steps, 0, 0);
        }
        List<String> got = list.snapshot();
        List<String> sorted = new ArrayList<>(input);
        Collections.sort(sorted);
        assertEquals(sorted, got);
    }

    @Test
    void concurrentSortersDoNotLoseElements() throws Exception {
        CustomLinkedList list = new CustomLinkedList();
        int k = 30;
        for (int i = 0; i < k; i++) {
            list.addFirst(String.format("item-%02d", i));
        }
        SortWorker w1 = new SortWorker(list, 0, 0);
        SortWorker w2 = new SortWorker(list, 0, 0);
        SortWorker w3 = new SortWorker(list, 0, 0);
        Thread t1 = new Thread(w1);
        Thread t2 = new Thread(w2);
        Thread t3 = new Thread(w3);
        t1.start();
        t2.start();
        t3.start();
        Thread.sleep(2000);
        t1.interrupt();
        t2.interrupt();
        t3.interrupt();
        t1.join(5000);
        t2.join(5000);
        t3.join(5000);

        assertTrue(w1.steps() + w2.steps() + w3.steps() > 0);

        List<String> got = list.snapshot();
        assertEquals(k, got.size());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            expected.add(String.format("item-%02d", i));
        }
        Collections.sort(expected);
        List<String> sortedGot = new ArrayList<>(got);
        Collections.sort(sortedGot);
        assertEquals(expected, sortedGot);

    }

    @Test
    void concurrentAddsAreNotLost() throws Exception {
        CustomLinkedList list = new CustomLinkedList();
        SortWorker w = new SortWorker(list, 0, 1);
        Thread t = new Thread(w);
        t.start();
        int m = 20;
        for (int i = 0; i < m; i++) {
            list.addFirst("v-" + i);
        }
        Thread.sleep(1000);
        t.interrupt();
        t.join(5000);
        List<String> got = list.snapshot();
        assertEquals(m, got.size());
        for (int i = 0; i < m; i++) {
            assertTrue(got.contains("v-" + i), "lost v-" + i);
        }
    }
}
