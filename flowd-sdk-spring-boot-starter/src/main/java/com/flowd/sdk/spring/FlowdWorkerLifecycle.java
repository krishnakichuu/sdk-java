package com.flowd.sdk.spring;

import com.flowd.sdk.worker.FlowdWorker;
import org.springframework.context.SmartLifecycle;

/**
 * Ties {@link FlowdWorker#run()} (a blocking call) to the Spring container
 * lifecycle, the same pattern Spring Kafka/Spring AMQP use for their own
 * long-lived consumer loops: {@code start()} runs it on a background
 * daemon thread as the context finishes refreshing, {@code stop()} calls
 * {@link FlowdWorker#shutdown()} during graceful application shutdown so
 * in-flight tasks' leases aren't abandoned mid-poll.
 *
 * <p>{@link #getPhase()} is deliberately high: this should start after
 * everything the registered activity beans might depend on (a database
 * connection pool, an HTTP client, ...) is already up, and stop before
 * those same beans are torn down.
 */
final class FlowdWorkerLifecycle implements SmartLifecycle {
    private final FlowdWorker worker;
    private volatile boolean running;

    FlowdWorkerLifecycle(FlowdWorker worker) {
        this.worker = worker;
    }

    @Override
    public void start() {
        Thread pollThread = new Thread(worker::run, "flowd-worker");
        pollThread.setDaemon(true);
        pollThread.start();
        running = true;
    }

    @Override
    public void stop() {
        worker.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
