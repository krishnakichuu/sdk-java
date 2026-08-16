package com.flowd.sdk.workflow;

import com.flowd.api.v1.Failure;
import com.flowd.sdk.internal.converter.DataConverter;
import com.flowd.sdk.internal.converter.DataConverterException;
import com.flowd.sdk.internal.replayer.ActivityFuture;
import com.flowd.sdk.internal.replayer.ActivityOutcome;
import com.flowd.sdk.internal.replayer.Coroutine;

/**
 * Returned by {@link WorkflowContext#executeActivity}; {@link #get} blocks
 * until the activity's result is known and unmarshals it via the
 * configured DataConverter. Mirrors workflow/context.go's Future (Go).
 */
public final class ActivityInvocation {
    private final ActivityFuture future;
    private final Coroutine co;
    private final DataConverter converter;

    ActivityInvocation(ActivityFuture future, Coroutine co, DataConverter converter) {
        this.future = future;
        this.co = co;
        this.converter = converter;
    }

    /**
     * Blocks until the activity completes or fails, unmarshaling a
     * successful result into resultType (pass null if the activity returns
     * nothing of interest).
     */
    public <T> T get(Class<T> resultType) throws DataConverterException {
        ActivityOutcome outcome = future.get(co);
        if (outcome.failure != null) {
            Failure f = outcome.failure;
            throw new ActivityException(f.getType(), f.getMessage());
        }
        if (resultType == null) {
            return null;
        }
        return converter.fromPayload(outcome.result, resultType);
    }
}
