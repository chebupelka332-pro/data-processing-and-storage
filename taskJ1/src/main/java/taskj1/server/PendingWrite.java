package taskj1.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

final class PendingWrite {
    private final ByteBuffer buffer;

    PendingWrite(byte[] response) {
        buffer = ByteBuffer.wrap(response).asReadOnlyBuffer();
    }

    boolean writeTo(WritableByteChannel channel) throws IOException {
        channel.write(buffer);
        return !buffer.hasRemaining();
    }
}
