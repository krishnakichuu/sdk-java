package com.flowd.examples.orderprocessing;

import com.flowd.sdk.client.FlowdClient;
import com.flowd.sdk.client.WorkflowClient;
import com.flowd.sdk.client.WorkflowOptions;

/**
 * Demonstrates the typed client stub end to end: starting a run, querying
 * it (via a second, attach-mode stub bound to the same workflow_id) while
 * it may still be in progress, then reading its typed result — all without
 * touching a raw {@code Payload} or workflow_type string anywhere in this
 * file.
 */
public final class OrderWorkflowStarter {
    public static void main(String[] args) throws Exception {
        String orderId = args.length > 0 ? args[0] : "order-" + System.currentTimeMillis();

        String target = System.getenv("FLOWD_ADDR");
        if (target == null || target.isEmpty()) {
            target = "localhost:7233";
        }

        FlowdClient conn = FlowdClient.dial(target);
        try {
            WorkflowClient client = WorkflowClient.newInstance(conn);

            OrderWorkflow startStub = client.newWorkflowStub(OrderWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(orderId)
                            .setTaskQueue(Constants.TASK_QUEUE)
                            .build());

            Thread runner = new Thread(() -> {
                String receipt = startStub.processOrder(orderId);
                System.out.println("receipt: " + receipt);
            });
            runner.start();

            // Query the same run through a second, attach-mode stub. The
            // very first attempt can race the start RPC itself (the
            // execution row not being visible to QueryWorkflowExecution
            // yet) — that race is a demo artifact of how fast this
            // single-activity workflow finishes locally, not something a
            // real caller needs to handle this way; retried a few times
            // with a short backoff rather than given a single try.
            OrderWorkflow queryStub = client.newWorkflowStub(OrderWorkflow.class, orderId);
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    System.out.println("status: " + queryStub.currentStatus());
                    break;
                } catch (Exception e) {
                    if (attempt == 5) {
                        System.out.println("(query never landed before the run finished: " + e.getMessage() + ")");
                    } else {
                        Thread.sleep(50);
                    }
                }
            }

            runner.join();
        } finally {
            conn.close();
        }
    }
}
