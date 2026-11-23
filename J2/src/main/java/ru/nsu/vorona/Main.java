package ru.nsu.vorona;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.out.println("Запуск: linked|sync <потоки> <задержка_внутри_мс> <задержка_между_мс>");
            return;
        }
        SortableStrings list = switch (args[0]) {
            case "linked" -> new LinkedStringList();
            case "sync" -> new SynchronizedStringList();
            default -> throw new IllegalArgumentException("Режим: linked или sync");
        };
        int threads = Integer.parseInt(args[1]);
        long insideDelay = Long.parseLong(args[2]);
        long betweenDelay = Long.parseLong(args[3]);

        try (Sorter sorter = new Sorter(list, threads, insideDelay, betweenDelay);
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            sorter.start();
            System.out.println("Вводите строки. Пустая строка показывает список, Ctrl+D/Ctrl+Z завершает программу.");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    System.out.println("--- Список ---");
                    for (String value : list) System.out.println(value);
                    System.out.println("Шагов сортировки: " + sorter.steps());
                } else {
                    for (int i = 0; i < line.length(); i += 80) {
                        list.addFirst(line.substring(i, Math.min(i + 80, line.length())));
                    }
                }
            }
        }
    }
}
