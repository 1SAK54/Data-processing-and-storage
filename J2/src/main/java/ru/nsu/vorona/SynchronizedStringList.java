package ru.nsu.vorona;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class SynchronizedStringList implements SortableStrings {
    private final List<String> values = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void addFirst(String value) {
        if (value == null) throw new IllegalArgumentException("Строка не может быть null");
        values.add(0, value);
    }

    @Override
    public boolean stepAt(int index, long insideDelayMs) throws InterruptedException {
        if (index < 0 || insideDelayMs < 0) throw new IllegalArgumentException("Неверный индекс или задержка");
        // Сравнение и обмен должны выполняться под одной блокировкой
        synchronized (values) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (index + 1 >= values.size()) return false;
            if (insideDelayMs > 0) Thread.sleep(insideDelayMs);
            if (values.get(index).compareTo(values.get(index + 1)) > 0) {
                Collections.swap(values, index, index + 1);
            }
            return true;
        }
    }

    @Override
    public List<String> snapshot() {
        synchronized (values) {
            return new ArrayList<>(values);
        }
    }

    @Override
    public Iterator<String> iterator() {
        return snapshot().iterator();
    }
}
