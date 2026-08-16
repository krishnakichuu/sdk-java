package com.flowd.sdk.internal.replayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors sdk/internal/replayer/dispatcher_test.go (Go) test-for-test. */
class DispatcherTest {

    @Test
    void executeRoundRunsToBlockingPoint() {
        Dispatcher d = new Dispatcher();
        List<String> trace = new CopyOnWriteArrayList<>();
        boolean[] ready = {false};

        Coroutine c = d.go(co -> {
            trace.add("start");
            while (!ready[0]) {
                co.yield();
            }
            trace.add("unblocked");
        });

        d.executeRound();
        assertEquals(List.of("start"), trace, "coroutine should block on yield() after the first round");
        assertFalse(c.isDone(), "coroutine reported done after blocking on yield");

        ready[0] = true;
        d.executeRound();
        assertEquals(List.of("start", "unblocked"), trace);
        assertTrue(c.isDone(), "coroutine should be done after returning");
    }

    @Test
    void executeRoundFixedOrder() {
        Dispatcher d = new Dispatcher();
        List<Integer> order = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 3; i++) {
            int captured = i;
            d.go(co -> order.add(captured));
        }
        d.executeRound();

        assertEquals(List.of(0, 1, 2), order,
                "dispatch order must be fixed creation order for replay determinism");
    }

    @Test
    void coroutineSpawnedMidRoundStartsNextRound() {
        // Not one of the Go test cases directly, but a genuine Java-specific
        // risk this port introduces: a coroutine spawning a child mid-round
        // (workflow.go called from inside a running coroutine) executes
        // concurrently with executeRound()'s own iteration, on a different
        // thread. The child must not run within the SAME round it was
        // created in — only starting on the next one — matching Go's slice
        // range capturing its length at the start of the loop.
        Dispatcher d = new Dispatcher();
        List<String> trace = new CopyOnWriteArrayList<>();

        d.go(co -> {
            trace.add("parent");
            d.go(child -> trace.add("child"));
        });

        d.executeRound();
        assertEquals(List.of("parent"), trace,
                "child spawned mid-round must not run in the same round it was created in");

        d.executeRound();
        assertEquals(List.of("parent", "child"), trace,
                "child spawned mid-round should run on the following round");
    }

    @Test
    void coroutinePanicIsRecovered() {
        Dispatcher d = new Dispatcher();
        d.go(co -> {
            throw new RuntimeException("boom");
        });
        d.executeRound();

        assertTrue(d.allDone(), "panicking coroutine should still be marked done");
        Throwable err = d.firstPanic();
        assertNotNull(err, "expected a recorded panic, got null");
        assertEquals("boom", err.getMessage());
    }
}
