package taskj1.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

final class Connection {
    final SocketChannel channel;
    final SelectionKey key;
    final ByteBuffer input = ByteBuffer.allocate(1024);
    final ByteArrayOutputStream name = new ByteArrayOutputStream();
    PendingWrite output;

    Connection(SocketChannel channel, SelectionKey key) {
        this.channel = channel;
        this.key = key;
    }
}
