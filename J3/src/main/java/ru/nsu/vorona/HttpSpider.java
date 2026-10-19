package ru.nsu.vorona;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

/**
 * Собирает сообщения со всех ресурсов, доступных из корневого пути сервера.
 * Запросы выполняются независимо друг от друга в виртуальных потоках.
 */
public final class HttpSpider {
    private final URI baseUri;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param serverUri адрес HTTP-сервера; путь в нём не используется
     */
    public HttpSpider(URI serverUri) {
        if (serverUri == null || !"http".equalsIgnoreCase(serverUri.getScheme())
                || serverUri.getHost() == null || serverUri.getUserInfo() != null
                || serverUri.getRawQuery() != null || serverUri.getRawFragment() != null) {
            throw new IllegalArgumentException("Expected an HTTP server URI");
        }
        baseUri = serverUri.resolve("/");
    }

    /**
     * Выполняет полный обход, начиная с /.
     *
     * @return сообщения в лексикографическом порядке
     * @throws IOException при сетевой ошибке или некорректном ответе
     * @throws InterruptedException если ожидание обхода было прервано
     */
    public List<String> crawl() throws IOException, InterruptedException {
        Set<URI> visited = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<IOException> errors = new ConcurrentLinkedQueue<>();
        Phaser phaser = new Phaser(1);

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            schedule(baseUri, client, executor, visited, messages, errors, phaser);
            phaser.arriveAndAwaitAdvance();
        }

        if (!errors.isEmpty()) {
            IOException failure = new IOException("Traversal failed (" + errors.size() + " errors)");
            for (IOException error : errors) {
                failure.addSuppressed(error);
            }
            throw failure;
        }

        List<String> result = new ArrayList<>(messages);
        result.sort(String::compareTo);
        return result;
    }

    /**
     * Планирует запрос, если этот путь ещё не встречался.
     * Новые задачи регистрируются в Phaser до завершения родительской задачи.
     */
    private void schedule(URI uri, HttpClient client, ExecutorService executor,
                          Set<URI> visited, ConcurrentLinkedQueue<String> messages,
                          ConcurrentLinkedQueue<IOException> errors, Phaser phaser) {
        URI normalized = uri.normalize();
        if (!normalized.isAbsolute() || !baseUri.getScheme().equalsIgnoreCase(normalized.getScheme())
                || !baseUri.getRawAuthority().equals(normalized.getRawAuthority())
                || normalized.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid resource URL: " + normalized);
        }
        if (!visited.add(normalized)) {
            return;
        }

        phaser.register();
        try {
            executor.submit(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder(normalized)
                            .timeout(Duration.ofSeconds(30)).GET().build();
                    HttpResponse<String> response = client.send(request,
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new IOException("HTTP " + response.statusCode() + " at " + normalized);
                    }
                    ServerResponse data = mapper.readValue(response.body(), ServerResponse.class);
                    if (data.message() == null || data.successors() == null
                            || data.successors().stream().anyMatch(s -> s == null || s.isBlank())) {
                        throw new IOException("Invalid JSON at " + normalized);
                    }
                    messages.add(data.message());
                    for (String path : data.successors()) {
                        URI next;
                        try {
                            next = baseUri.resolve(path);
                        } catch (IllegalArgumentException e) {
                            throw new IOException("Invalid successor at " + normalized + ": " + path, e);
                        }
                        try {
                            schedule(next, client, executor, visited, messages, errors, phaser);
                        } catch (IllegalArgumentException e) {
                            throw new IOException("Invalid successor at " + normalized + ": " + path, e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.add(new IOException("Interrupted at " + normalized, e));
                } catch (IOException e) {
                    errors.add(new IOException("Request failed at " + normalized, e));
                } catch (RuntimeException e) {
                    errors.add(new IOException("Invalid response at " + normalized, e));
                } finally {
                    phaser.arriveAndDeregister();
                }
            });
        } catch (RuntimeException e) {
            phaser.arriveAndDeregister();
            throw e;
        }
    }
}
