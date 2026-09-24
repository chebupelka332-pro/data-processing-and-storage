package taskj1.server;

import taskj1.crypto.KeyMaterialGenerator;
import taskj1.protocol.Protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class KeyServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(KeyServer.class.getName());
    private static final AtomicInteger SERVER_IDS = new AtomicInteger();

    private final Selector selector;
    private final ServerSocketChannel listener;
    private final ExecutorService workers;
    private final KeyMaterialGenerator generator;
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final Map<String, Entry> cache = new HashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Thread ioThread;
    private final int port;
    private volatile boolean running = true;
    private volatile IOException failure;

    public static KeyServer start(InetSocketAddress address,
                                  int workerCount,
                                  KeyMaterialGenerator generator
    ) throws IOException {
        KeyServer server = new KeyServer(address, workerCount, generator);
        server.ioThread.start();
        return server;
    }

    private KeyServer(InetSocketAddress address, int workerCount, KeyMaterialGenerator generator) throws IOException {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        this.generator = Objects.requireNonNull(generator);
        selector = Selector.open();
        ServerSocketChannel channel = null;
        try {
            channel = ServerSocketChannel.open();
            channel.configureBlocking(false);
            channel.bind(address, 256);
            channel.register(selector, SelectionKey.OP_ACCEPT);
            port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
            listener = channel;
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                channel.close();
            }
            selector.close();
            throw e;
        }
        String prefix = "taskj1-" + SERVER_IDS.incrementAndGet();
        AtomicInteger workerIds = new AtomicInteger();
        workers = Executors.newFixedThreadPool(workerCount, task -> {
            Thread thread = new Thread(task, prefix + "-keygen-" + workerIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        ioThread = new Thread(this::runLoop, prefix + "-io");
    }

    public int port() {
        return port;
    }

    public void awaitTermination() throws InterruptedException, IOException {
        stopped.await();
        if (failure != null) {
            throw failure;
        }
    }

    private void runLoop() {
        try {
            while (running) {
                processCompletions();
                selector.select();
                if (!running) {
                    break;
                }
                processCompletions();
                var selected = selector.selectedKeys().iterator();
                while (running && selected.hasNext()) {
                    SelectionKey key = selected.next();
                    selected.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        acceptConnections();
                        continue;
                    }
                    Connection connection = (Connection) key.attachment();
                    try {
                        if (key.isReadable()) {
                            readRequest(connection);
                        }
                        if (key.isValid() && key.isWritable()) {
                            if (connection.output.writeTo(connection.channel)) {
                                closeConnection(connection);
                            }
                        }
                    } catch (IOException | CancelledKeyException e) {
                        LOG.log(Level.FINE, "Client disconnected", e);
                        closeConnection(connection);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            failure = new IOException("Server I/O loop failed", e);
            LOG.log(Level.SEVERE, "Server I/O loop failed", e);
        } finally {
            running = false;
            workers.shutdownNow();
            for (SelectionKey key : selector.keys()) {
                try {
                    key.channel().close();
                } catch (IOException e) {
                    LOG.log(Level.FINE, "Channel close failed", e);
                }
            }
            try {
                selector.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Selector close failed", e);
            }
            cache.clear();
            completions.clear();
            stopped.countDown();
        }
    }

    private void acceptConnections() throws IOException {
        for (int i = 0; i < 64; i++) {
            SocketChannel channel = listener.accept();
            if (channel == null) {
                return;
            }
            try {
                channel.configureBlocking(false);
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
                key.attach(new Connection(channel, key));
            } catch (IOException e) {
                channel.close();
                LOG.log(Level.FINE, "Could not initialize client", e);
            }
        }
    }

    private void readRequest(Connection connection) throws IOException {
        ByteBuffer buffer = connection.input;
        buffer.clear();
        int read = connection.channel.read(buffer);
        if (read < 0) {
            closeConnection(connection);
            return;
        }
        buffer.flip();
        while (buffer.hasRemaining()) {
            int value = Byte.toUnsignedInt(buffer.get());
            if (value == 0) {
                if (connection.name.size() == 0) {
                    send(connection, Protocol.error("Name must not be empty"));
                } else {
                    requestGeneration(connection, connection.name.toString(StandardCharsets.US_ASCII));
                }
                return;
            }
            if (value > 127 || connection.name.size() >= Protocol.MAX_NAME_BYTES) {
                send(connection, Protocol.error("Name must contain at most 1024 non-NUL ASCII bytes"));
                return;
            }
            connection.name.write(value);
        }
    }

    private void requestGeneration(Connection connection, String name) {
        connection.key.interestOps(0);
        Entry entry = cache.get(name);
        if (entry != null && entry.response != null) {
            send(connection, entry.response);
            return;
        }
        if (entry != null) {
            entry.waiters.add(connection);
            return;
        }
        entry = new Entry();
        entry.waiters.add(connection);
        cache.put(name, entry);
        workers.execute(() -> generate(name));
        LOG.fine("Scheduled key generation");
    }

    private void generate(String name) {
        Completion completion;
        try {
            completion = new Completion(name, Protocol.success(generator.generate(name)), null);
        } catch (Exception e) {
            completion = new Completion(name, null, e);
        }
        if (running) {
            completions.add(completion);
            selector.wakeup();
        }
    }

    private void processCompletions() {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            Entry entry = cache.get(completion.name());
            byte[] response;
            if (completion.error() != null) {
                cache.remove(completion.name());
                response = Protocol.error("Key generation failed; retry the request");
                LOG.log(Level.WARNING, "Key generation failed", completion.error());
            } else {
                response = completion.response();
                entry.response = response;
                LOG.fine("Key generation completed");
            }
            for (Connection connection : entry.waiters) {
                if (connection.key.isValid()) {
                    send(connection, response);
                }
            }
            entry.waiters.clear();
        }
    }

    private static void send(Connection connection, byte[] response) {
        connection.output = new PendingWrite(response);
        connection.key.interestOps(SelectionKey.OP_WRITE);
    }

    private static void closeConnection(Connection connection) {
        connection.key.cancel();
        try {
            connection.channel.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Client close failed", e);
        }
    }

    @Override
    public void close() {
        running = false;
        selector.wakeup();
        if (Thread.currentThread() == ioThread) {
            return;
        }
        boolean interrupted = false;
        while (true) {
            try {
                stopped.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

}
