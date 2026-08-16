package com.flowd.examples.helloworkflow;

public final class Constants {
    /** Distinct from Go's "helloworkflow" queue to keep this Java-only proof
     *  isolated. Workflow/activity type names still match Go's exactly
     *  (SimpleWorkflow, GreetActivity), so pointing both at the same queue
     *  name later is a one-line change for a cross-language interop test. */
    public static final String TASK_QUEUE = "helloworkflow-java";

    private Constants() {
    }
}
