package taskj1.server;

import java.util.ArrayList;
import java.util.List;

final class Entry {
    final List<Connection> waiters = new ArrayList<>();
    byte[] response;
}
