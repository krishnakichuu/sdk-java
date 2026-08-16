package com.flowd.examples.helloworkflow;

import com.flowd.sdk.client.FlowdClient;
import com.flowd.sdk.client.WorkflowRun;

/** Mirrors examples/helloworkflow/starter/main.go (Go). */
public final class HelloWorkflowStarter {
    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "World";

        String target = System.getenv("FLOWD_ADDR");
        if (target == null || target.isEmpty()) {
            target = "localhost:7233";
        }

        FlowdClient client = FlowdClient.dial(target);
        try {
            FlowdClient.StartWorkflowOptions opts = new FlowdClient.StartWorkflowOptions();
            opts.id = "helloworkflow-java-" + System.currentTimeMillis();
            opts.taskQueue = Constants.TASK_QUEUE;

            WorkflowRun run = client.startWorkflow(opts, "SimpleWorkflow", name);
            System.out.printf("started workflow_id=%s run_id=%s%n", run.workflowId, run.runId);

            String result = run.get(String.class);
            System.out.println(result);
        } finally {
            client.close();
        }
    }
}
