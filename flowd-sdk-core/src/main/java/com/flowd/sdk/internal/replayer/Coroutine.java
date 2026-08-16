package com.flowd.sdk.internal.replayer;

import java.util.concurrent.SynchronousQueue;

/**
 * One cooperatively-scheduled unit of workflow code. Mirrors
 * sdk/internal/replayer/dispatcher.go's Coroutine (Go) 1:1: Go backs this
 * with a goroutine and an unbuffered channel pair; this backs it with a
 * virtual thread (Java 21+, cheap enough to use one per coroutine the same
 * way goroutines are) and a {@link SynchronousQueue} pair, which has the
 * same direct-handoff, no-buffering semantics as an unbuffered Go channel.
 *
 * <p>A Dispatcher only ever lets one coroutine execute application code at
 * a time: {@link #yield()} hands control back to the dispatcher and blocks
 * until explicitly resumed.
 */
public final class Coroutine {
    final SynchronousQueue<Object> resumeCh = new SynchronousQueue<>();
    final SynchronousQueue<Object> yieldCh = new SynchronousQueue<>();
    private static final Object TOKEN = new Object();

    volatile boolean done = false;
    volatile Throwable panicErr = null;

    /**
     * Hands control back to the dispatcher and blocks until this coroutine
     * is resumed on a later round. Every blocking primitive exposed by
     * WorkflowContext ({@code Future.get}, {@code sleep}) is implemented in
     * terms of this — it is the only way workflow code may block, which is
     * what makes replay possible.
     */
    public void yield() {
        try {
            yieldCh.put(TOKEN);
            resumeCh.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("coroutine interrupted", e);
        }
    }

    public boolean isDone() {
        return done;
    }
}
