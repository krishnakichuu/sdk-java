package com.flowd.sdk.internal.replayer;

import com.flowd.api.v1.Payload;

/**
 * Acts on a signal's decoded payload — see
 * {@link Execution#setSignalHandler}. Unlike {@link QueryHandler}, it
 * returns nothing and declares no checked exception: signaling is
 * asynchronous and fire-and-forget by design (see
 * {@code FlowdClient.signalWorkflow}, which does not wait for or expose
 * one), so there is no reply channel for a handler to answer — or fail —
 * through. Mirrors execution.go's SignalHandler (Go).
 */
@FunctionalInterface
public interface SignalHandler {
    void handle(Payload args);
}
