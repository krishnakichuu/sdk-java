package com.flowd.examples.helloworkflow;

import com.flowd.sdk.client.FlowdClient;
import com.flowd.sdk.worker.FlowdWorker;

/** Mirrors examples/helloworkflow/worker/main.go (Go). */
public final class HelloWorkflowWorker {
    public static void main(String[] args) {
        String target = System.getenv("FLOWD_ADDR");
        if (target == null || target.isEmpty()) {
            target = "localhost:7233";
        }

        FlowdClient client = FlowdClient.dial(target);
        FlowdWorker worker = new FlowdWorker(client, Constants.TASK_QUEUE);
        worker.registerWorkflow("SimpleWorkflow", String.class, SimpleWorkflow::execute);
        worker.registerActivity("GreetActivity", String.class, GreetActivity::execute);

        System.out.printf("helloworkflow-java worker polling task queue \"%s\" at %s%n", Constants.TASK_QUEUE, target);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            worker.shutdown();
            client.close();
        }));

        worker.run();
    }
}
