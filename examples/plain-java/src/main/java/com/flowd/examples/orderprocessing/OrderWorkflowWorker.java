package com.flowd.examples.orderprocessing;

import com.flowd.sdk.client.FlowdClient;
import com.flowd.sdk.worker.FlowdWorker;

/** Plain Java, no DI framework: manual wiring, a blocking main(). */
public final class OrderWorkflowWorker {
    public static void main(String[] args) {
        String target = System.getenv("FLOWD_ADDR");
        if (target == null || target.isEmpty()) {
            target = "localhost:7233";
        }

        FlowdClient client = FlowdClient.dial(target);
        FlowdWorker worker = new FlowdWorker(client, Constants.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(new PaymentActivitiesImpl());

        System.out.printf("orderprocessing-java worker polling task queue \"%s\" at %s%n", Constants.TASK_QUEUE, target);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            worker.shutdown();
            client.close();
        }));

        worker.run();
    }
}
