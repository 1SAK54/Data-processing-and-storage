package ru.nsu.vorona;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class Sorter implements AutoCloseable {
    private final SortableStrings list;
    private final long insideDelayMs;
    private final long betweenDelayMs;
    private final List<Thread> workers = new ArrayList<>();
    private final LongAdder steps = new LongAdder();

    public Sorter(SortableStrings list, int threadCount, long insideDelayMs, long betweenDelayMs) {
        if (threadCount < 1 || insideDelayMs < 0 || betweenDelayMs < 0) {
            throw new IllegalArgumentException("Неверные параметры сортировки");
        }
        this.list = list;
        this.insideDelayMs = insideDelayMs;
        this.betweenDelayMs = betweenDelayMs;
        for (int i = 0; i < threadCount; i++) {
            workers.add(new Thread(this::run, "sorter-" + (i + 1)));
        }
    }

    public void start() {
        for (Thread worker : workers) {
            worker.start();
        }
    }

    private void run() {
        int index = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (list.stepAt(index, insideDelayMs)) {
                    // Сравнение без перестановки тоже считается шагом
                    steps.increment();
                    index++;
                } else {
                    index = 0;
                }
                if (betweenDelayMs > 0) Thread.sleep(betweenDelayMs);
                else Thread.yield();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long steps() {
        return steps.sum();
    }

    @Override
    public void close() {
        for (Thread worker : workers) worker.interrupt();
        for (Thread worker : workers) {
            try {
                worker.join(5000);
                if (worker.isAlive()) throw new IllegalStateException("Поток не остановился: " + worker.getName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ожидание потоков прервано", e);
            }
        }
    }
}
