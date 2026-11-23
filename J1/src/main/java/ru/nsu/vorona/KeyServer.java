package ru.nsu.vorona;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class KeyServer implements AutoCloseable {
    private static final class Client {
        final SocketChannel channel;
        final SelectionKey key;
        final ByteArrayOutputStream name = new ByteArrayOutputStream();
        ByteBuffer response;

        Client(SocketChannel channel, SelectionKey key) {
            this.channel = channel;
            this.key = key;
        }
    }

    private record Ready(Client client, byte[] response) {}

    private final Selector selector;
    private final ServerSocketChannel listener;
    private final ExecutorService generators;
    private final KeyGenerator keyGenerator;
    private final Map<String, CompletableFuture<KeyGenerator.Result>> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean serving = new AtomicBoolean(false);
    private final AtomicBoolean resourcesClosed = new AtomicBoolean(false);

    public KeyServer(String host, int port, int workers, KeyGenerator generator) throws IOException {
        if (workers < 1 || port < 0 || port > 65535) throw new IllegalArgumentException("Неверные параметры сервера");
        this.keyGenerator = generator;
        this.generators = Executors.newFixedThreadPool(workers);
        this.selector = Selector.open();
        this.listener = ServerSocketChannel.open();
        try {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(host, port), 256);
            listener.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException e) {
            listener.close();
            selector.close();
            generators.shutdownNow();
            throw e;
        }
    }

    public int port() throws IOException {
        return ((InetSocketAddress) listener.getLocalAddress()).getPort();
    }

    public void serve() throws IOException {
        synchronized (this) {
            if (!running.get()) throw new IllegalStateException("Сервер закрыт");
            if (!serving.compareAndSet(false, true)) throw new IllegalStateException("Сервер уже запущен");
        }
        try {
            while (running.get()) {
                flushReady();
                selector.select(500);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.isAcceptable()) accept();
                        else if (key.isReadable()) read((Client) key.attachment());
                        else if (key.isWritable()) write((Client) key.attachment());
                    } catch (IOException | RuntimeException e) {
                        if (key.attachment() instanceof Client client) closeClient(client);
                        else throw e;
                    }
                }
            }
        } finally {
            running.set(false);
            releaseResources();
        }
    }

    private void accept() throws IOException {
        SocketChannel channel;
        while ((channel = listener.accept()) != null) {
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
            key.attach(new Client(channel, key));
        }
    }

    private void read(Client client) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(1024);
        int count = client.channel.read(bytes);
        if (count < 0) {
            closeClient(client);
            return;
        }
        bytes.flip();
        // Имя может прийти несколькими чтениями
        while (bytes.hasRemaining()) {
            byte b = bytes.get();
            if (b == 0) {
                byte[] data = client.name.toByteArray();
                if (!Protocol.validName(data)) {
                    setResponse(client, Protocol.error(Protocol.BAD_REQUEST, "Invalid ASCII name"));
                } else {
                    String name = new String(data, StandardCharsets.US_ASCII);
                    client.key.interestOps(0);
                    request(name).whenComplete((result, error) -> {
                        if (!running.get()) return;
                        byte[] response = error == null
                                ? Protocol.success(result)
                                : Protocol.error(Protocol.SERVER_ERROR, "Failed to generate key");
                        // Генератор не пишет в сокет, только передаёт готовый ответ
                        ready.add(new Ready(client, response));
                        selector.wakeup();
                    });
                }
                return;
            }
            client.name.write(b);
            if (client.name.size() > Protocol.MAX_NAME) {
                setResponse(client, Protocol.error(Protocol.BAD_REQUEST, "Name too long"));
                return;
            }
        }
    }

    private CompletableFuture<KeyGenerator.Result> request(String name) {
        CompletableFuture<KeyGenerator.Result> future = new CompletableFuture<>();
        // Сохраняем future до запуска задачи: второй запрос должен дождаться того же ключа
        CompletableFuture<KeyGenerator.Result> existing = cache.putIfAbsent(name, future);
        if (existing != null) return existing;
        generators.execute(() -> {
            try {
                future.complete(keyGenerator.create(name));
            } catch (Exception e) {
                cache.remove(name, future);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void flushReady() {
        Ready result;
        while ((result = ready.poll()) != null) {
            Client client = result.client();
            if (client.key.isValid()) setResponse(client, result.response());
        }
    }

    private void setResponse(Client client, byte[] bytes) {
        client.response = ByteBuffer.wrap(bytes);
        client.key.interestOps(SelectionKey.OP_WRITE);
    }

    private void write(Client client) throws IOException {
        // Остаток буфера отправится при следующем событии записи
        client.channel.write(client.response);
        if (!client.response.hasRemaining()) closeClient(client);
    }

    private void closeClient(Client client) {
        try {
            client.key.cancel();
            client.channel.close();
        } catch (IOException ignored) {
            // Ошибка закрытия соединения не должна останавливать сервер
        }
    }

    @Override
    public synchronized void close() throws IOException {
        running.set(false);
        selector.wakeup();
        if (!serving.get()) releaseResources();
    }

    private void releaseResources() throws IOException {
        if (!resourcesClosed.compareAndSet(false, true)) return;
        generators.shutdownNow();
        try {
            // Закрытие Selector не закрывает клиентские каналы
            for (SelectionKey key : selector.keys()) {
                if (key.attachment() instanceof Client client) closeClient(client);
            }
            ready.clear();
            listener.close();
        } finally {
            selector.close();
        }
    }
}
