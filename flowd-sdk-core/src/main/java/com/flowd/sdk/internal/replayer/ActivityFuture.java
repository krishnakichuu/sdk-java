package com.flowd.sdk.internal.replayer;

/** Resolves once the activity it represents has a recorded outcome (from
 *  history, or from a later task once it completes). Mirrors execution.go's
 *  ActivityFuture (Go). */
public final class ActivityFuture {
    private final Execution exec;
    public final long activityId;

    ActivityFuture(Execution exec, long activityId) {
        this.exec = exec;
        this.activityId = activityId;
    }

    /** Blocks (yielding co) until this activity's outcome is known. */
    public ActivityOutcome get(Coroutine co) {
        return exec.activityFutureGet(co, activityId);
    }
}
