package com.flowd.sdk.activity;

/**
 * ActivityContext is passed to activity functions. Unlike WorkflowContext,
 * activities have no determinism constraint — only their recorded result
 * is replayed, never their code — so activity code may perform arbitrary
 * I/O directly, no restricted surface needed. Mirrors
 * sdk/activity/context.go's Context (Go).
 */
public final class ActivityContext {
    private final Info info;

    public ActivityContext(Info info) {
        this.info = info;
    }

    public Info getInfo() {
        return info;
    }
}
