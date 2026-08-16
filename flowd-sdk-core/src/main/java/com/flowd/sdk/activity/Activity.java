package com.flowd.sdk.activity;

import com.flowd.sdk.internal.sync.ActivityThreadContext;

/**
 * Static entry point for annotation-based activity implementations — methods
 * on an {@link ActivityInterface} implementation take only business
 * parameters (no explicit context argument, unlike the lower-level {@link
 * ActivityHandler}/{@code ActivityContext}-parameter API), and reach the
 * current task's metadata through here instead, the same convention
 * Temporal's Java SDK uses. (The lower-level {@code ActivityHandler}/{@code
 * ActivityContext}-parameter API in {@code com.flowd.sdk.worker} still
 * takes context as an explicit parameter; both are supported.)
 *
 * <pre>{@code
 * @Override
 * public String chargeCard(ChargeRequest request) {
 *     Info info = Activity.getExecutionContext().getInfo();
 *     log.info("attempt {} for activity {}", info.attempt(), info.activityId());
 *     ...
 * }
 * }</pre>
 *
 * <p>Throws {@link IllegalStateException} if called from anywhere other
 * than an activity implementation method currently being invoked by a
 * flowd Worker.
 */
public final class Activity {
    private Activity() {
    }

    public static ActivityContext getExecutionContext() {
        return ActivityThreadContext.current();
    }
}
