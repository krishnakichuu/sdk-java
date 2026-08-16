package com.flowd.sdk.client;

import com.flowd.api.v1.RetryPolicy;

import java.time.Duration;

/**
 * Configures a {@link WorkflowClient#newWorkflowStub} start call. Mirrors
 * {@link FlowdClient.StartWorkflowOptions}'s fields exactly (that type
 * still exists, and is what this is translated into) but as an immutable,
 * validated builder — the idiom every other production Java SDK uses for a
 * many-optional-field configuration object, in place of the mutable public
 * fields the lower-level type uses.
 */
public final class WorkflowOptions {
    private final String workflowId;
    private final String taskQueue;
    private final Duration workflowExecutionTimeout;
    private final Duration workflowRunTimeout;
    private final Duration workflowTaskTimeout;
    private final RetryPolicy retryPolicy;
    private final String requestId;

    private WorkflowOptions(Builder b) {
        this.workflowId = b.workflowId;
        this.taskQueue = b.taskQueue;
        this.workflowExecutionTimeout = b.workflowExecutionTimeout;
        this.workflowRunTimeout = b.workflowRunTimeout;
        this.workflowTaskTimeout = b.workflowTaskTimeout;
        this.retryPolicy = b.retryPolicy;
        this.requestId = b.requestId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTaskQueue() {
        return taskQueue;
    }

    public Duration getWorkflowExecutionTimeout() {
        return workflowExecutionTimeout;
    }

    public Duration getWorkflowRunTimeout() {
        return workflowRunTimeout;
    }

    public Duration getWorkflowTaskTimeout() {
        return workflowTaskTimeout;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public String getRequestId() {
        return requestId;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private String workflowId;
        private String taskQueue;
        private Duration workflowExecutionTimeout;
        private Duration workflowRunTimeout;
        private Duration workflowTaskTimeout;
        private RetryPolicy retryPolicy;
        private String requestId;

        private Builder() {
        }

        /** Required: the business identifier this run starts (or attaches) under. */
        public Builder setWorkflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        /** Required: which task queue a worker must be polling for this workflow_type. */
        public Builder setTaskQueue(String taskQueue) {
            this.taskQueue = taskQueue;
            return this;
        }

        public Builder setWorkflowExecutionTimeout(Duration d) {
            this.workflowExecutionTimeout = d;
            return this;
        }

        public Builder setWorkflowRunTimeout(Duration d) {
            this.workflowRunTimeout = d;
            return this;
        }

        public Builder setWorkflowTaskTimeout(Duration d) {
            this.workflowTaskTimeout = d;
            return this;
        }

        public Builder setRetryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        /**
         * Idempotency key: a repeated start with the same (namespace,
         * workflowId, requestId) returns the existing run instead of
         * erroring or starting a duplicate. Left unset, the server
         * generates one — meaning repeated calls are NOT automatically
         * idempotent unless a stable requestId is supplied here.
         */
        public Builder setRequestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public WorkflowOptions build() {
            if (workflowId == null || workflowId.isBlank()) {
                throw new IllegalArgumentException("WorkflowOptions.workflowId is required");
            }
            if (taskQueue == null || taskQueue.isBlank()) {
                throw new IllegalArgumentException("WorkflowOptions.taskQueue is required");
            }
            return new WorkflowOptions(this);
        }
    }
}
