package com.flowd.sdk.internal.sync;

import com.flowd.sdk.activity.ActivityContext;

/**
 * Binds the {@link ActivityContext} of the activity task currently
 * executing on this virtual thread, mirroring {@link WorkflowThreadContext}
 * for the activity side: it's what lets {@link
 * com.flowd.sdk.activity.Activity#getExecutionContext()} work without an
 * annotation-based activity method needing an explicit context parameter.
 *
 * <p>Safe for the same structural reason as the workflow side: each
 * activity task is dispatched onto its own dedicated virtual thread (see
 * {@code FlowdWorker.pollActivityTasks}), used for exactly one task and
 * never reused, so there is never more than one activity execution's state
 * visible through this thread-local at a time.
 */
public final class ActivityThreadContext {
    private static final ThreadLocal<ActivityContext> CURRENT = new ThreadLocal<>();

    private ActivityThreadContext() {
    }

    public static void bind(ActivityContext ctx) {
        CURRENT.set(ctx);
    }

    public static void unbind() {
        CURRENT.remove();
    }

    public static ActivityContext current() {
        ActivityContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "Activity.getExecutionContext() may only be called from within an activity "
                            + "implementation method executing on a flowd Worker");
        }
        return ctx;
    }
}
