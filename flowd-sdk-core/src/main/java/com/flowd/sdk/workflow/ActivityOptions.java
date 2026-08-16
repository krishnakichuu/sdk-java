package com.flowd.sdk.workflow;

import com.flowd.api.v1.RetryPolicy;

import java.time.Duration;

/**
 * Configures a single {@link WorkflowContext#executeActivity} /
 * {@link Workflow#newActivityStub} call. The public counterpart of
 * {@link com.flowd.sdk.internal.replayer.ActivityOptions}, which this
 * translates to internally — workflow-author-facing code should depend on
 * this type, never the internal one (see ARCHITECTURE.md §10's
 * {@code internal.*}-leak note).
 */
public record ActivityOptions(RetryPolicy retryPolicy, Duration scheduleToStartTimeout, Duration startToCloseTimeout) {
    public static final ActivityOptions DEFAULT = new ActivityOptions(null, null, null);

    com.flowd.sdk.internal.replayer.ActivityOptions toInternal() {
        return new com.flowd.sdk.internal.replayer.ActivityOptions(retryPolicy, scheduleToStartTimeout, startToCloseTimeout);
    }
}
