package com.flowd.examples.helloworkflow;

import com.flowd.sdk.workflow.WorkflowContext;

/** Calls GreetActivity once and returns its result. Mirrors
 *  examples/helloworkflow/workflow.go's SimpleWorkflow (Go) exactly. */
public final class SimpleWorkflow {
    private SimpleWorkflow() {
    }

    public static String execute(WorkflowContext ctx, String name) throws Exception {
        // Notice this prints TWICE per run, not once — that's not a bug.
        // Workflow code is re-run from the top on every workflow task (the
        // deterministic replay model — see ADR-0001): once when it first
        // schedules GreetActivity, and again after the activity completes,
        // when history is replayed from event 1 to reconstruct state before
        // resuming. The activity itself, below, has no such constraint and
        // runs exactly once per real attempt.
        System.out.println("[worker] SimpleWorkflow running for: " + name);
        return ctx.executeActivity("GreetActivity", name).get(String.class);
    }
}
