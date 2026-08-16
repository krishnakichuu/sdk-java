package com.flowd.sdk.workflow;

import com.flowd.sdk.activity.ActivityInterface;
import com.flowd.sdk.internal.registry.AnnotationSupport;
import com.flowd.sdk.internal.sync.ActivityStubInvocationHandler;
import com.flowd.sdk.internal.sync.WorkflowThreadContext;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Static entry point for annotation-based workflow implementations — the
 * counterpart to {@link com.flowd.sdk.activity.Activity} on the activity
 * side. Every method here delegates to whichever {@link WorkflowContext}
 * is bound to the coroutine currently executing (see {@link
 * WorkflowThreadContext}), so workflow code never has to carry a context
 * parameter through by hand the way the lower-level, {@code
 * WorkflowContext}-parameter API still does — both are fully interoperable
 * against the same replay engine underneath, and a codebase may mix them.
 *
 * <p>Every method here throws {@link IllegalStateException} if called from
 * outside workflow code executing on a flowd Worker (e.g. from an activity
 * implementation, or a plain background thread) — there is no bound
 * context to act on.
 */
public final class Workflow {
    private Workflow() {
    }

    /** The time the current workflow task started, recorded in history — never wall-clock. */
    public static Instant now() {
        return WorkflowThreadContext.current().now();
    }

    /** Blocks the calling coroutine until d has elapsed on the workflow's timeline (a server-fired timer). */
    public static void sleep(Duration d) {
        WorkflowThreadContext.current().sleep(d);
    }

    /**
     * Returns a typed client for activityInterface: each method call
     * schedules that activity and blocks (deterministically) until its
     * result is known, returning it directly.
     *
     * <pre>{@code
     * private final PaymentActivities payments =
     *     Workflow.newActivityStub(PaymentActivities.class,
     *         ActivityOptions.builder().startToCloseTimeout(Duration.ofSeconds(30)).build());
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public static <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
        if (!activityInterface.isInterface() || !activityInterface.isAnnotationPresent(ActivityInterface.class)) {
            throw new IllegalArgumentException(
                    activityInterface.getName() + " must be an interface annotated @ActivityInterface");
        }
        WorkflowThreadContext.current(); // fail fast if not called from workflow code
        return (T) Proxy.newProxyInstance(
                activityInterface.getClassLoader(),
                new Class<?>[]{activityInterface},
                new ActivityStubInvocationHandler(activityInterface, options != null ? options : ActivityOptions.DEFAULT));
    }

    public static <T> T newActivityStub(Class<T> activityInterface) {
        return newActivityStub(activityInterface, ActivityOptions.DEFAULT);
    }

    /**
     * Registers handler to answer queries of queryType — see {@link
     * WorkflowContext#setQueryHandler}. The annotation-based worker calls
     * this automatically for every {@code @QueryMethod} on a registered
     * workflow implementation; call it directly only for a query type not
     * expressed as an annotated method (e.g. dynamically named queries).
     */
    public static void setQueryHandler(String queryType, Class<?> argType, Function<Object, Object> handler) {
        WorkflowThreadContext.current().setQueryHandler(queryType, argType, handler);
    }

    /**
     * Registers handler to act on signals of signalType — see
     * {@link WorkflowContext#setSignalHandler}. The annotation-based
     * worker calls this automatically for every {@code @SignalMethod} on a
     * registered workflow implementation; call it directly only for a
     * signal not expressed as an annotated method.
     */
    public static void setSignalHandler(String signalName, Class<?> argType, Consumer<Object> handler) {
        WorkflowThreadContext.current().setSignalHandler(signalName, argType, handler);
    }

    /** Continue-as-new by explicit workflow_type — see {@link WorkflowContext#continueAsNew}. */
    public static void continueAsNew(String workflowType, Object input, ContinueAsNewOptions options) {
        WorkflowThreadContext.current().continueAsNew(workflowType, input, options);
    }

    public static void continueAsNew(String workflowType, Object input) {
        continueAsNew(workflowType, input, ContinueAsNewOptions.DEFAULT);
    }

    /** Continue-as-new to a different (or the same) {@code @WorkflowInterface}, resolving its registered name. */
    public static <T> void continueAsNew(Class<T> workflowInterface, Object input, ContinueAsNewOptions options) {
        continueAsNew(AnnotationSupport.workflowTypeName(workflowInterface), input, options);
    }

    public static <T> void continueAsNew(Class<T> workflowInterface, Object input) {
        continueAsNew(workflowInterface, input, ContinueAsNewOptions.DEFAULT);
    }
}
