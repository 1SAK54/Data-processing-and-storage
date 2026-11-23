package ru.nsu.vorona;

import java.util.Locale;

public final class BenchmarkMain {
    private BenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        long durationMs = args.length > 2 ? Long.parseLong(args[2]) : 5000;
        long insideMs = args.length > 3 ? Long.parseLong(args[3]) : 10;
        long betweenMs = args.length > 4 ? Long.parseLong(args[4]) : 10;
        if (n < 2 || threads < 1 || durationMs < 1 || insideMs < 0 || betweenMs < 0
                || (insideMs == 0 && betweenMs == 0)) {
            throw new IllegalArgumentException("Нужны n >= 2, время > 0 и ненулевая задержка");
        }

        double passMs = (n - 1.0) * insideMs + n * (double) betweenMs;
        double ideal = threads * (double) durationMs * (n - 1) / passMs;
        double linkedLimit = insideMs == 0 ? ideal
                : Math.min(ideal, ((n + 1L) / 3) * (double) durationMs / insideMs);
        double syncLimit = insideMs == 0 ? ideal : Math.min(ideal, (double) durationMs / insideMs);
        System.out.printf(Locale.US, "Параметры: n=%d, k=%d, T=%d мс, a=%d мс, b=%d мс%n",
                n, threads, durationMs, insideMs, betweenMs);
        System.out.printf(Locale.US, "Теория linked (верхняя оценка без накладных расходов): %.0f шагов%n", linkedLimit);
        System.out.printf(Locale.US, "Теория sync (верхняя оценка без накладных расходов): %.0f шагов%n", syncLimit);
        for (String mode : new String[]{"linked", "sync"}) {
            SortableStrings list = mode.equals("linked") ? new LinkedStringList() : new SynchronizedStringList();
            for (int i = 0; i < n; i++) list.addFirst(String.format("%05d", i));
            Sorter sorter = new Sorter(list, threads, insideMs, betweenMs);
            try (sorter) {
                sorter.start();
                Thread.sleep(durationMs);
            }
            System.out.println(mode + ": " + sorter.steps() + " шагов");
        }
    }
}
