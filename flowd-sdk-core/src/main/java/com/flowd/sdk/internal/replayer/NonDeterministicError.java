package com.flowd.sdk.internal.replayer;

/**
 * Raised when replaying history against the current workflow code would
 * schedule a different activity/timer than what history recorded at the
 * same position — e.g. the workflow code changed in a way that isn't
 * compatible with in-flight executions. Mirrors
 * sdk/internal/replayer/execution.go's NonDeterministicError (Go): thrown
 * from inside a coroutine, so it's caught by Dispatcher.go's Throwable
 * catch the same as any workflow panic, and distinguishable from an
 * ordinary workflow failure via {@code instanceof}.
 */
public final class NonDeterministicError extends RuntimeException {
    public NonDeterministicError(String message) {
        super("non-deterministic workflow: " + message);
    }
}
