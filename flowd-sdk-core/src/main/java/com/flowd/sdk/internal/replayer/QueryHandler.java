package com.flowd.sdk.internal.replayer;

import com.flowd.api.v1.Payload;

/**
 * Answers one query type against the workflow's current in-memory state.
 * Invoked directly (see {@link Execution#invokeQueryHandler}), not through
 * a coroutine's own yield/resume cycle, so it must be a pure, synchronous
 * read of already-captured workflow state — no blocking primitive
 * (activity execution, sleep) is safe to call from inside one. Mirrors
 * execution.go's QueryHandler (Go).
 */
@FunctionalInterface
public interface QueryHandler {
    Payload handle(Payload args) throws Exception;
}
