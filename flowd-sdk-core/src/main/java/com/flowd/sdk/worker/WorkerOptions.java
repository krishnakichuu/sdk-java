package com.flowd.sdk.worker;

import com.flowd.sdk.internal.converter.DataConverter;
import com.flowd.sdk.internal.converter.JsonDataConverter;

import java.time.Duration;
import java.util.List;

/**
 * Configures a {@link FlowdWorker}. Mirrors sdk/worker's Options (Go) field
 * for field; a zero-value {@code Builder.build()} (every field left unset)
 * reproduces this SDK's original, pre-sticky-cache behavior exactly, the
 * same "additive, defaults preserve prior behavior" property the Go side's
 * Phase 2 features were built with.
 */
public final class WorkerOptions {
    static final int DEFAULT_MAX_CONCURRENT_ACTIVITIES = 200;
    static final int DEFAULT_MAX_CACHED_WORKFLOW_EXECUTIONS = 1000;
    static final Duration DEFAULT_STICKY_SCHEDULE_TO_START_TIMEOUT = Duration.ofSeconds(5);

    final DataConverter converter;
    final int maxConcurrentActivities;
    final int maxCachedWorkflowExecutions;
    final Duration stickyScheduleToStartTimeout;
    final List<Integer> taskQueuePartitions;

    private WorkerOptions(Builder b) {
        this.converter = b.converter != null ? b.converter : JsonDataConverter.INSTANCE;
        this.maxConcurrentActivities = b.maxConcurrentActivities > 0
                ? b.maxConcurrentActivities : DEFAULT_MAX_CONCURRENT_ACTIVITIES;
        this.maxCachedWorkflowExecutions = b.maxCachedWorkflowExecutions > 0
                ? b.maxCachedWorkflowExecutions : DEFAULT_MAX_CACHED_WORKFLOW_EXECUTIONS;
        this.stickyScheduleToStartTimeout = b.stickyScheduleToStartTimeout != null
                ? b.stickyScheduleToStartTimeout : DEFAULT_STICKY_SCHEDULE_TO_START_TIMEOUT;
        this.taskQueuePartitions = b.taskQueuePartitions != null ? List.copyOf(b.taskQueuePartitions) : List.of();
    }

    public static WorkerOptions defaultOptions() {
        return newBuilder().build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private DataConverter converter;
        private int maxConcurrentActivities;
        private int maxCachedWorkflowExecutions;
        private Duration stickyScheduleToStartTimeout;
        private List<Integer> taskQueuePartitions;

        private Builder() {
        }

        /** Default: {@link JsonDataConverter#INSTANCE}. Must match whatever the workflow's other workers/clients use. */
        public Builder setDataConverter(DataConverter converter) {
            this.converter = converter;
            return this;
        }

        /** Bounds how many activity tasks this worker processes at once. Default 200. */
        public Builder setMaxConcurrentActivities(int n) {
            this.maxConcurrentActivities = n;
            return this;
        }

        /** Bounds this worker's sticky Execution cache (see {@link ExecutionCache}). Default 1000. */
        public Builder setMaxCachedWorkflowExecutions(int n) {
            this.maxCachedWorkflowExecutions = n;
            return this;
        }

        /** How long a cached run's next task is preferentially routed back to this worker. Default 5s. */
        public Builder setStickyScheduleToStartTimeout(Duration d) {
            this.stickyScheduleToStartTimeout = d;
            return this;
        }

        /**
         * Restricts this worker to the listed task_queue_partition values —
         * empty/unset (the default) means every partition. Meaningless
         * unless the server is configured with more than one partition
         * (FLOWD_NUM_TASK_QUEUE_PARTITIONS).
         */
        public Builder setTaskQueuePartitions(List<Integer> partitions) {
            this.taskQueuePartitions = partitions;
            return this;
        }

        public WorkerOptions build() {
            return new WorkerOptions(this);
        }
    }
}
