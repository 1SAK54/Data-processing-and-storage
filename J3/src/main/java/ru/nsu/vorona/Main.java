package ru.nsu.vorona;

import java.net.URI;

/** Точка входа для запуска обхода из командной строки. */
public final class Main {
    private Main() { }

    /**
     * Запускает паука и печатает сообщения в отсортированном порядке.
     *
     * @param args необязательный адрес сервера; по умолчанию localhost:8080
     * @throws Exception если сервер недоступен или вернул некорректные данные
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            System.err.println("Usage: java ... ru.nsu.vorona.Main [http://host:port/]");
            System.exit(2);
        }
        URI uri = URI.create(args.length == 0 ? "http://localhost:8080/" : args[0]);
        for (String message : new HttpSpider(uri).crawl()) {
            System.out.println(message);
        }
    }
}
