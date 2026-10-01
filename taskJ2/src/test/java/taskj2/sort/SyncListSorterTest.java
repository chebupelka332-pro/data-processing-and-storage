package taskj2.sort;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncListSorterTest {

    @Test
    void sorterSortsSmallList() throws Exception {
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        synchronized (list) {
            list.add("d");
            list.add("c");
            list.add("b");
            list.add("a");
        }
        SyncListSorter sorter = new SyncListSorter(list, 0, 0);
        Thread t = new Thread(sorter);
        t.start();
        Thread.sleep(1500);
        t.interrupt();
        t.join(5000);
        synchronized (list) {
            assertEquals(List.of("a", "b", "c", "d"), new ArrayList<>(list));
        }
        assertTrue(sorter.steps() > 0);
    }
}
