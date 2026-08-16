package com.flowd.sdk.worker;

import com.flowd.sdk.activity.ActivityContext;

/**
 * A registered activity implementation. Unlike WorkflowHandler, both a
 * thrown checked Exception and an unchecked RuntimeException are treated
 * identically here (both become RespondActivityTaskFailed) — activities
 * have no determinism/replay concern, so there's no "must propagate to the
 * dispatcher" distinction to preserve, matching Go's processActivityTask.
 */
@FunctionalInterface
public interface ActivityHandler<I, O> {
    O execute(ActivityContext ctx, I input) throws Exception;
}
