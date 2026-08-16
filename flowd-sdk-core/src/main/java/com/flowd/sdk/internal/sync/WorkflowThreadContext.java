package com.flowd.sdk.internal.sync;

import com.flowd.sdk.workflow.WorkflowContext;

/**
 * Binds the {@link WorkflowContext} of the coroutine currently executing on
 * this virtual thread, so that the static accessors in {@link
 * com.flowd.sdk.workflow.Workflow} — the annotation-driven API's DX layer —
 * can find "the current workflow" without every call site threading a
 * context parameter through by hand, the way the lower-level {@code
 * WorkflowContext}-parameter API still requires.
 *
 * <p>This is safe specifically because of how coroutines are scheduled (see
 * {@link com.flowd.sdk.internal.replayer.Dispatcher}): each coroutine is
 * backed by exactly one virtual thread for its entire lifetime, and a
 * Dispatcher only ever lets one coroutine run application code at a time.
 * {@code bind} is called once, right before a coroutine's workflow method
 * body starts running, and {@code unbind} once it returns (see the
 * annotation-based workflow adapter in {@code com.flowd.sdk.worker}) — so
 * the thread-local is always exactly the context of whichever workflow
 * instance's code is presently executing.
 *
 * <p>SDK-internal: application code should go through {@link
 * com.flowd.sdk.workflow.Workflow}'s static methods, not this class
 * directly.
 */
public final class WorkflowThreadContext {
    private static final ThreadLocal<WorkflowContext> CURRENT = new ThreadLocal<>();

    private WorkflowThreadContext() {
    }

    public static void bind(WorkflowContext ctx) {
        CURRENT.set(ctx);
    }

    public static void unbind() {
        CURRENT.remove();
    }

    /**
     * Returns the WorkflowContext bound to the calling thread, or throws if
     * none is — the case when application code calls a {@code Workflow}
     * static method from somewhere other than workflow code running on a
     * worker (e.g. from an activity implementation, or from a plain unit
     * test that didn't go through the replay engine).
     */
    public static WorkflowContext current() {
        WorkflowContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "Workflow.* may only be called from within workflow code executing on a "
                            + "flowd Worker — no WorkflowContext is bound to the current thread");
        }
        return ctx;
    }
}
