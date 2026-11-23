package ru.nsu.vorona;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ListsTest {
    private static List<SortableStrings> implementations() {
        return List.of(new LinkedStringList(), new SynchronizedStringList());
    }

    @Test
    void addsToHead() {
        for (SortableStrings list : implementations()) {
            list.addFirst("a");
            list.addFirst("b");
            assertEquals(List.of("b", "a"), list.snapshot());
        }
    }

    @Test
    void swapsAdjacentItems() throws Exception {
        for (SortableStrings list : implementations()) {
            list.addFirst("a");
            list.addFirst("c");
            list.addFirst("b");
            assertTrue(list.stepAt(1, 0));
            assertEquals(List.of("b", "a", "c"), list.snapshot());
            assertFalse(list.stepAt(2, 0));
        }
    }

    @Test
    void snapshotDoesNotWaitBetweenSteps() throws Exception {
        for (SortableStrings list : implementations()) {
            list.addFirst("x");
            list.addFirst("y");
            list.addFirst("z");
            try (Sorter sorter = new Sorter(list, 1, 0, 5000)) {
                sorter.start();
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    while (sorter.steps() == 0) Thread.sleep(1);
                });
                assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                    assertEquals(List.of("y", "z", "x"), list.snapshot());
                    list.addFirst("a");
                    assertEquals(List.of("a", "y", "z", "x"), list.snapshot());
                });
            }
        }
    }

    @Test
    void insideDelayHoldsLocksAndInterruptionReleasesThem() throws Exception {
        for (SortableStrings list : implementations()) {
            list.addFirst("a");
            list.addFirst("z");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread step = delayedStep(list, 0, failure);
            Thread snapshot = new Thread(list::snapshot);
            step.start();
            try {
                awaitState(step, Thread.State.TIMED_WAITING);
                snapshot.start();
                awaitState(snapshot, list instanceof LinkedStringList ? Thread.State.WAITING : Thread.State.BLOCKED);
            } finally {
                step.interrupt();
                step.join(2000);
                snapshot.join(2000);
            }
            assertFalse(step.isAlive());
            assertFalse(snapshot.isAlive());
            assertNull(failure.get());
            assertEquals(List.of("z", "a"), list.snapshot());
            assertTrue(list.stepAt(0, 0));
            assertEquals(List.of("a", "z"), list.snapshot());
        }
    }

    @Test
    void linkedStepsCanOverlapButArrayListStepsAreSerialized() throws Exception {
        for (SortableStrings list : implementations()) {
            for (int i = 0; i < 8; i++) list.addFirst("value-" + i);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread tail = delayedStep(list, 4, failure);
            Thread head = delayedStep(list, 0, failure);
            tail.start();
            try {
                awaitState(tail, Thread.State.TIMED_WAITING);
                head.start();
                awaitState(head, list instanceof LinkedStringList ? Thread.State.TIMED_WAITING : Thread.State.BLOCKED);
            } finally {
                tail.interrupt();
                head.interrupt();
                tail.join(2000);
                head.join(2000);
            }
            assertFalse(tail.isAlive());
            assertFalse(head.isAlive());
            assertNull(failure.get());
        }
    }

    @Test
    void concurrentSortKeepsAllItems() throws Exception {
        for (SortableStrings list : implementations()) {
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < 60; i++) {
                expected.add("value-" + i);
                list.addFirst("value-" + i);
            }
            try (Sorter sorter = new Sorter(list, 4, 0, 0)) {
                sorter.start();
                for (int i = 60; i < 200; i++) {
                    expected.add("value-" + i);
                    list.addFirst("value-" + i);
                    List<String> snapshot = list.snapshot();
                    assertEquals(expected.size(), snapshot.size());
                    assertEquals(expected, new HashSet<>(snapshot));
                }
                List<String> sorted = new ArrayList<>(expected);
                Collections.sort(sorted);
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (!list.snapshot().equals(sorted)) Thread.sleep(1);
                });
                assertTrue(sorter.steps() > 0);
            }
        }
    }

    @Test
    void sorterCountsSteps() throws Exception {
        SortableStrings list = new LinkedStringList();
        for (int i = 0; i < 10; i++) list.addFirst("x" + i);
        try (Sorter sorter = new Sorter(list, 2, 1, 1)) {
            sorter.start();
            Thread.sleep(150);
            assertTrue(sorter.steps() > 0);
        }
    }

    private static Thread delayedStep(SortableStrings list, int index, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                list.stepAt(index, 5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
    }

    private static void awaitState(Thread thread, Thread.State state) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (thread.getState() != state) {
                assertTrue(thread.isAlive(), "Поток завершился до ожидаемого состояния " + state);
                Thread.sleep(1);
            }
        });
    }
}
