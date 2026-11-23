package ru.nsu.vorona;

import java.util.List;

public interface SortableStrings extends Iterable<String> {
    void addFirst(String value);

    boolean stepAt(int index, long insideDelayMs) throws InterruptedException;

    List<String> snapshot();
}
