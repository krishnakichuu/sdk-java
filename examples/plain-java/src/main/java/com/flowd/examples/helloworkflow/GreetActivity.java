package com.flowd.examples.helloworkflow;

import com.flowd.sdk.activity.ActivityContext;

/** Mirrors examples/helloworkflow/workflow.go's GreetActivity (Go). */
public final class GreetActivity {
    private GreetActivity() {
    }

    public static String execute(ActivityContext ctx, String name) {
        // Visible proof the worker is the one doing the actual work —
        // activities have no replay/determinism constraint, so this prints
        // exactly once per real attempt, never more.
        System.out.printf("[worker] GreetActivity received: name=%s (activityId=%d, attempt=%d)%n",
                name, ctx.getInfo().activityId(), ctx.getInfo().attempt());
        String greeting = "Hello, " + name + "!";
        System.out.println("[worker] GreetActivity result: " + greeting);
        return greeting;
    }
}
