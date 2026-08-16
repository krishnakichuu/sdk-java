package com.flowd.sdk.workflow;

import com.flowd.api.v1.RetryPolicy;

import java.time.Duration;

/**
 * Configures a {@link WorkflowContext#continueAsNew} /
 * {@link Workflow#continueAsNew} call. The public counterpart of
 * {@link com.flowd.sdk.internal.replayer.Execution.ContinueAsNewOptions},
 * which this translates to internally — workflow-author-facing code should
 * depend on this type, never the internal one (see ARCHITECTURE.md §10's
 * {@code internal.*}-leak note). A zero-value (all-null) instance means
 * "same as the current run" for every field.
 */
public record ContinueAsNewOptions(String taskQueue, RetryPolicy retryPolicy,
                                    Duration workflowRunTimeout, Duration workflowTaskTimeout) {
    public static final ContinueAsNewOptions DEFAULT = new ContinueAsNewOptions(null, null, null, null);

    com.flowd.sdk.internal.replayer.Execution.ContinueAsNewOptions toInternal() {
        return new com.flowd.sdk.internal.replayer.Execution.ContinueAsNewOptions(
                taskQueue, retryPolicy, workflowRunTimeout, workflowTaskTimeout);
    }
}
