package com.flowd.sdk.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Binds the {@code flowd.*} configuration namespace. Every field has a
 * sensible default matching a local, single-worker development setup
 * (mirrors how the plain-Java examples default {@code FLOWD_ADDR} to
 * {@code localhost:7233}) — a Spring Boot application only needs to set
 * {@code flowd.task-queue} to get a working worker; everything else is
 * opt-in tuning.
 *
 * <pre>{@code
 * flowd:
 *   address: flowd.internal:7233
 *   namespace: orders
 *   task-queue: order-processing
 *   worker:
 *     max-concurrent-activities: 500
 *     base-packages: com.example.orders.workflows
 * }</pre>
 */
@ConfigurationProperties(prefix = "flowd")
public class FlowdProperties {

    /** {@code host:port} of the flowd server. */
    private String address = "localhost:7233";

    private String namespace = "default";

    /**
     * The task queue this application's worker polls. Leaving this unset
     * disables worker auto-configuration entirely (see {@link
     * FlowdWorkerAutoConfiguration}) — a client-only application (one that
     * only starts/signals/queries workflows, never executes them) has no
     * reason to run a worker.
     */
    private String taskQueue;

    private final Worker worker = new Worker();

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getTaskQueue() {
        return taskQueue;
    }

    public void setTaskQueue(String taskQueue) {
        this.taskQueue = taskQueue;
    }

    public Worker getWorker() {
        return worker;
    }

    public static class Worker {
        /** Set false to run this application as a flowd client only, even if task-queue is configured. */
        private boolean enabled = true;

        private int maxConcurrentActivities;
        private int maxCachedWorkflowExecutions;
        private Duration stickyScheduleToStartTimeout;

        /**
         * Packages to scan for {@code @WorkflowInterface} implementation
         * classes. Defaults to the package(s) Spring Boot already scans
         * for {@code @Component}s (the {@code @SpringBootApplication}
         * class's own package) — workflow implementation classes
         * themselves are deliberately NOT {@code @Component} beans (see
         * {@code FlowdWorker.registerWorkflowImplementationTypes}'s doc on
         * why a fresh instance per execution matters), so this scan is
         * separate from Spring's own component scan, not a reuse of it.
         */
        private List<String> basePackages;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxConcurrentActivities() {
            return maxConcurrentActivities;
        }

        public void setMaxConcurrentActivities(int maxConcurrentActivities) {
            this.maxConcurrentActivities = maxConcurrentActivities;
        }

        public int getMaxCachedWorkflowExecutions() {
            return maxCachedWorkflowExecutions;
        }

        public void setMaxCachedWorkflowExecutions(int maxCachedWorkflowExecutions) {
            this.maxCachedWorkflowExecutions = maxCachedWorkflowExecutions;
        }

        public Duration getStickyScheduleToStartTimeout() {
            return stickyScheduleToStartTimeout;
        }

        public void setStickyScheduleToStartTimeout(Duration stickyScheduleToStartTimeout) {
            this.stickyScheduleToStartTimeout = stickyScheduleToStartTimeout;
        }

        public List<String> getBasePackages() {
            return basePackages;
        }

        public void setBasePackages(List<String> basePackages) {
            this.basePackages = basePackages;
        }
    }
}
