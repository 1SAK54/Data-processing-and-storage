package ru.nsu.vorona;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты обхода на локальном HTTP-сервере.
 */
class HttpSpiderTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverExecutor;
    private URI base;

    /** Создаёт тестовый сервер на свободном порту перед каждым тестом. */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    /** Останавливает тестовый сервер после выполнения теста. */
    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    /** Регистрирует путь, возвращающий заданный JSON. */
    private void route(String path, String json) {
        server.createContext(path, exchange -> respond(exchange, 200, json));
    }

    /** Отправляет HTTP-ответ с указанным статусом и содержимым. */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    /** Проверяет обход сервера с одним ресурсом. */
    @Test
    void rootOnly() throws Exception {
        route("/", "{\"message\":\"one\",\"successors\":[]}");
        server.start();
        assertEquals(List.of("one"), new HttpSpider(base).crawl());
    }

    /** Проверяет обход нескольких ветвей и сортировку сообщений. */
    @Test
    void traversesBranchesAndSortsMessages() throws Exception {
        route("/", "{\"message\":\"zebra\",\"successors\":[\"/a\",\"/b\"]}");
        route("/a", "{\"message\":\"apple\",\"successors\":[\"/c\"]}");
        route("/b", "{\"message\":\"bird\",\"successors\":[]}");
        route("/c", "{\"message\":\"cat\",\"successors\":[]}");
        server.start();
        assertEquals(List.of("apple", "bird", "cat", "zebra"), new HttpSpider(base).crawl());
    }

    /** Проверяет, что повторяющийся адрес запрашивается один раз. */
    @Test
    void visitsDuplicatePathOnlyOnce() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        route("/", "{\"message\":\"root\",\"successors\":[\"/x\",\"/x\"]}");
        server.createContext("/x", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"message\":\"x\",\"successors\":[]}");
        });
        server.start();
        assertEquals(List.of("root", "x"), new HttpSpider(base).crawl());
        assertEquals(1, requests.get());
    }

    /** Проверяет завершение обхода при циклических ссылках. */
    @Test
    void handlesCycleWithoutInfiniteLoop() throws Exception {
        route("/", "{\"message\":\"root\",\"successors\":[\"/x\"]}");
        route("/x", "{\"message\":\"x\",\"successors\":[\"/\"]}");
        server.start();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertEquals(List.of("root", "x"), new HttpSpider(base).crawl()));
    }

    /** Проверяет, что запросы к разным ветвям выполняются параллельно. */
    @Test
    void branchesAreRequestedConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        route("/", "{\"message\":\"root\",\"successors\":[\"/a\",\"/b\"]}");
        for (String path : List.of("/a", "/b")) {
            server.createContext(path, exchange -> {
                bothStarted.countDown();
                boolean concurrent;
                try {
                    concurrent = bothStarted.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    concurrent = false;
                }
                respond(exchange, concurrent ? 200 : 503,
                        "{\"message\":\"" + path + "\",\"successors\":[]}");
            });
        }
        server.start();
        assertEquals(List.of("/a", "/b", "root"), new HttpSpider(base).crawl());
    }

    /** Проверяет обработку ответа сервера с ошибочным HTTP-статусом. */
    @Test
    void reportsHttpError() throws Exception {
        server.createContext("/", exchange -> respond(exchange, 500, "error"));
        server.start();
        assertThrows(IOException.class, () -> new HttpSpider(base).crawl());
    }

    /** Проверяет обработку некорректного JSON. */
    @Test
    void reportsMalformedJson() throws Exception {
        route("/", "not valid json");
        server.start();
        assertThrows(IOException.class, () -> new HttpSpider(base).crawl());
    }

    /** Проверяет, что программа дожидается медленного дочернего ресурса. */
    @Test
    void waitsForSlowChild() throws Exception {
        route("/", "{\"message\":\"root\",\"successors\":[\"/slow\"]}");
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"message\":\"slow\",\"successors\":[]}");
        });
        server.start();
        assertEquals(List.of("root", "slow"), new HttpSpider(base).crawl());
    }

    /** Проверяет вывод кириллицы из ответа в кодировке UTF-8. */
    @Test
    void readsUtf8Messages() throws Exception {
        route("/", "{\"message\":\"Привет, мир!\",\"successors\":[]}");
        server.start();
        assertEquals(List.of("Привет, мир!"), new HttpSpider(base).crawl());
    }

    /** Проверяет завершение обхода на большом числе независимых ресурсов. */
    @Test
    void traversesManyResources() throws Exception {
        StringBuilder rootJson = new StringBuilder("{\"message\":\"root\",\"successors\":[");
        for (int i = 0; i < 150; i++) {
            if (i > 0) rootJson.append(',');
            rootJson.append("\"/item").append(i).append("\"");
            route("/item" + i, "{\"message\":\"item\",\"successors\":[]}");
        }
        rootJson.append("]}");
        route("/", rootJson.toString());
        server.start();
        List<String> result = new HttpSpider(base).crawl();
        assertEquals(151, result.size());
        assertEquals("root", result.get(150));
    }

    /** Проверяет, что отсутствующее поле successors считается ошибкой. */
    @Test
    void rejectsIncompleteJson() throws Exception {
        route("/", "{\"message\":\"x\"}");
        server.start();
        assertThrows(IOException.class, () -> new HttpSpider(base).crawl());
    }

    /** Проверяет запрет перехода по ссылке на другой сервер. */
    @Test
    void rejectsExternalUrl() throws Exception {
        route("/", "{\"message\":\"root\",\"successors\":[\"http://example.com/x\"]}");
        server.start();
        assertThrows(IOException.class, () -> new HttpSpider(base).crawl());
    }

}
