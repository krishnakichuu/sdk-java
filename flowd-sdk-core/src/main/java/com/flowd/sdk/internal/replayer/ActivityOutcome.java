package com.flowd.sdk.internal.replayer;

import com.flowd.api.v1.Failure;
import com.flowd.api.v1.Payload;

/** Mirrors execution.go's ActivityOutcome (Go): exactly one of result/failure is set. */
public final class ActivityOutcome {
    public final Payload result;
    public final Failure failure;

    private ActivityOutcome(Payload result, Failure failure) {
        this.result = result;
        this.failure = failure;
    }

    public static ActivityOutcome ofResult(Payload result) {
        return new ActivityOutcome(result, null);
    }

    public static ActivityOutcome ofFailure(Failure failure) {
        return new ActivityOutcome(null, failure);
    }
}
