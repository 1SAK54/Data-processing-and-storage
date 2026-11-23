package ru.nsu.vorona;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public final class LinkedStringList implements SortableStrings {
    private static final class Node {
        final String value;
        final ReentrantLock lock = new ReentrantLock();
        Node next;

        Node(String value, Node next) {
            this.value = value;
            this.next = next;
        }
    }

    // Служебный узел для вставки и перестановки в начале списка
    private final Node head = new Node(null, null);

    @Override
    public void addFirst(String value) {
        if (value == null) throw new IllegalArgumentException("Строка не может быть null");
        head.lock.lock();
        try {
            head.next = new Node(value, head.next);
        } finally {
            head.lock.unlock();
        }
    }

    @Override
    public boolean stepAt(int index, long insideDelayMs) throws InterruptedException {
        if (index < 0 || insideDelayMs < 0) throw new IllegalArgumentException("Неверный индекс или задержка");
        Node previous = head;
        previous.lock.lockInterruptibly();
        try {
            for (int i = 0; i < index; i++) {
                Node next = previous.next;
                if (next == null) return false;
                next.lock.lockInterruptibly();
                // Нельзя отпускать текущий узел, пока не захвачен следующий
                previous.lock.unlock();
                previous = next;
            }

            Node left = previous.next;
            if (left == null) return false;
            left.lock.lockInterruptibly();
            try {
                Node right = left.next;
                if (right == null) return false;
                right.lock.lockInterruptibly();
                try {
                    if (insideDelayMs > 0) Thread.sleep(insideDelayMs);
                    if (left.value.compareTo(right.value) > 0) {
                        // Переставляем узлы, сохраняя их значения
                        previous.next = right;
                        left.next = right.next;
                        right.next = left;
                    }
                    return true;
                } finally {
                    right.lock.unlock();
                }
            } finally {
                left.lock.unlock();
            }
        } finally {
            previous.lock.unlock();
        }
    }

    @Override
    public List<String> snapshot() {
        List<String> values = new ArrayList<>();
        Node previous = head;
        previous.lock.lock();
        try {
            while (previous.next != null) {
                Node current = previous.next;
                current.lock.lock();
                values.add(current.value);
                previous.lock.unlock();
                previous = current;
            }
        } finally {
            previous.lock.unlock();
        }
        return values;
    }

    @Override
    public Iterator<String> iterator() {
        // Итератор обходит снимок без удержания блокировок
        return snapshot().iterator();
    }
}
