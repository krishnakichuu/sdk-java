package com.flowd.sdk.internal.replayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Runs workflow code as cooperatively-scheduled coroutines — never raw
 * threads directly usable by the caller — so history replay can
 * reconstruct identical execution order. Mirrors
 * sdk/internal/replayer/dispatcher.go's Dispatcher (Go) 1:1: same
 * fixed-creation-order round semantics, just backed by virtual threads
 * instead of goroutines.
 *
 * <p>{@link #executeRound()} resumes every live coroutine exactly once, in
 * a fixed (creation) order, and each coroutine runs until its next
 * {@code yield()} or completion — this fixed order is what makes replay
 * deterministic.
 */
public final class Dispatcher {
    // CopyOnWriteArrayList: workflow.go(fn) lets a running coroutine spawn a
    // child coroutine from its own virtual thread, concurrently with
    // executeRound()'s iteration on the caller's thread. A plain ArrayList
    // would risk ConcurrentModificationException or worse under that race.
    private final List<Coroutine> coroutines = new CopyOnWriteArrayList<>();
    private static final Object TOKEN = new Object();

    /**
     * Starts a new coroutine running fn. It does not begin executing until
     * this dispatcher's first {@link #executeRound()} call.
     */
    public Coroutine go(Consumer<Coroutine> fn) {
        Coroutine c = new Coroutine();
        coroutines.add(c);

        Thread.ofVirtual().start(() -> {
            try {
                c.resumeCh.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                fn.accept(c);
            } catch (Throwable t) {
                c.panicErr = t;
            } finally {
                c.done = true;
                try {
                    c.yieldCh.put(TOKEN);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        return c;
    }

    /**
     * Resumes every not-yet-done coroutine exactly once, in creation order,
     * waiting for each to yield or finish before resuming the next. Any
     * coroutine already blocked in yield() that has nothing new to observe
     * simply yields again immediately, a harmless no-op round-trip —
     * callers pace how many rounds they run, so this never spins.
     *
     * <p>Iterates by index up to the coroutine count observed at the start
     * of this round — not a live view — so a coroutine started mid-round
     * (via workflow.go from another coroutine already running this round)
     * is only picked up on the *next* round. This mirrors Go's {@code for
     * _, c := range d.coroutines}, where ranging over a slice captures its
     * length at the start of the loop; appending to the backing slice
     * during iteration does not extend that same range.
     */
    public void executeRound() {
        int n = coroutines.size();
        for (int i = 0; i < n; i++) {
            Coroutine c = coroutines.get(i);
            if (c.done) {
                continue;
            }
            try {
                c.resumeCh.put(TOKEN);
                c.yieldCh.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("dispatcher interrupted mid-round", e);
            }
        }
    }

    /** Reports whether every coroutine has finished (returned or thrown). */
    public boolean allDone() {
        for (Coroutine c : coroutines) {
            if (!c.done) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the first coroutine's uncaught throwable, if any, treated as
     * a workflow execution failure (not a {@link NonDeterministicError},
     * which is a specific subtype of this, distinguished with
     * {@code instanceof}).
     */
    public Throwable firstPanic() {
        for (Coroutine c : coroutines) {
            if (c.panicErr != null) {
                return c.panicErr;
            }
        }
        return null;
    }
}
