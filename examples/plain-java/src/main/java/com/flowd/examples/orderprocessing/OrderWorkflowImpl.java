package com.flowd.examples.orderprocessing;

import com.flowd.sdk.workflow.ActivityOptions;
import com.flowd.sdk.workflow.Workflow;

import java.time.Duration;

/**
 * A fresh instance of this class is constructed for every workflow task's
 * replay (see {@code FlowdWorker.registerWorkflowImplementationTypes}) —
 * {@code status} is safe as an ordinary instance field specifically because
 * of that: each replay reconstructs it identically by re-running this same
 * code from the top, the same determinism guarantee the lower-level API
 * gets from never mutating anything outside the workflow function's own
 * local state.
 */
public final class OrderWorkflowImpl implements OrderWorkflow {
    private final PaymentActivities payments = Workflow.newActivityStub(
            PaymentActivities.class,
            new ActivityOptions(null, null, Duration.ofSeconds(30)));

    private volatile String status = "STARTED";

    @Override
    public String processOrder(String orderId) {
        // Runs twice per real attempt, same as helloworkflow's
        // SimpleWorkflow — once scheduling chargeCard, again on replay
        // after it completes. status is query-visible in between.
        System.out.println("[worker] OrderWorkflow processing: " + orderId);
        status = "CHARGING";
        String receipt = payments.chargeCard(orderId);
        status = "COMPLETED";
        return receipt;
    }

    @Override
    public String currentStatus() {
        return status;
    }
}
