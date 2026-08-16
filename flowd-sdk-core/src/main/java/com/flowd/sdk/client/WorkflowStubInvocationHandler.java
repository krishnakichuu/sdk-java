package com.flowd.sdk.client;

import com.flowd.sdk.internal.converter.DataConverterException;
import com.flowd.sdk.internal.converter.MethodArguments;
import com.flowd.sdk.internal.registry.AnnotationSupport;
import com.flowd.sdk.workflow.QueryMethod;
import com.flowd.sdk.workflow.SignalMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Backs the proxy returned by {@link WorkflowClient#newWorkflowStub}.
 * Package-private: constructed only by WorkflowClient, which is also this
 * package's only caller of {@link WorkflowRun}'s package-private
 * constructor — application code never sees this class, only the
 * interface it implements.
 *
 * <p>Two modes, matching the two WorkflowClient factory methods:
 * {@code options != null} means "start a new run on first @WorkflowMethod
 * call"; {@code options == null} (workflowId/runId supplied instead) means
 * "attach to an existing run for query/signal use" — calling the
 * @WorkflowMethod itself is rejected in that mode, since there is nothing
 * to start it with.
 */
final class WorkflowStubInvocationHandler implements InvocationHandler {
    private final Class<?> workflowInterface;
    private final Method workflowMethod;
    private final WorkflowOptions options;
    private final FlowdClient client;

    private volatile WorkflowRun run;

    /** Start mode: run is created lazily on the first @WorkflowMethod call. */
    WorkflowStubInvocationHandler(Class<?> workflowInterface, WorkflowOptions options, FlowdClient client) {
        this.workflowInterface = workflowInterface;
        this.workflowMethod = AnnotationSupport.requireWorkflowMethod(workflowInterface);
        this.options = options;
        this.client = client;
    }

    /** Attach mode: bound to an already-known run; queries/signals only. */
    WorkflowStubInvocationHandler(Class<?> workflowInterface, String workflowId, String runId, FlowdClient client) {
        this.workflowInterface = workflowInterface;
        this.workflowMethod = AnnotationSupport.requireWorkflowMethod(workflowInterface);
        this.options = null;
        this.client = client;
        this.run = new WorkflowRun(client, workflowId, runId != null ? runId : "");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "WorkflowStub[" + workflowInterface.getName()
                        + (run != null ? ", workflowId=" + run.workflowId : "") + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args != null ? args[0] : null);
                default -> null;
            };
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        if (method.equals(workflowMethod)) {
            return startAndAwaitResult(method, args);
        }
        if (method.isAnnotationPresent(QueryMethod.class)) {
            return query(method, args);
        }
        if (method.isAnnotationPresent(SignalMethod.class)) {
            return signal(method, args);
        }
        throw new UnsupportedOperationException(
                "method " + method + " is neither the @WorkflowMethod nor annotated @QueryMethod/@SignalMethod");
    }

    private Object startAndAwaitResult(Method method, Object[] args) throws Exception {
        if (options == null) {
            throw new IllegalStateException(
                    "stub for " + workflowInterface.getName() + " was created to attach to an existing run "
                            + "(WorkflowClient.newWorkflowStub(Class, workflowId, runId)) — it cannot start a new one; "
                            + "use WorkflowClient.newWorkflowStub(Class, WorkflowOptions) instead");
        }
        Object arg = MethodArguments.pack(args);
        FlowdClient.StartWorkflowOptions startOpts = new FlowdClient.StartWorkflowOptions();
        startOpts.id = options.getWorkflowId();
        startOpts.taskQueue = options.getTaskQueue();
        startOpts.workflowExecutionTimeout = options.getWorkflowExecutionTimeout();
        startOpts.workflowRunTimeout = options.getWorkflowRunTimeout();
        startOpts.workflowTaskTimeout = options.getWorkflowTaskTimeout();
        startOpts.retryPolicy = options.getRetryPolicy();
        startOpts.requestId = options.getRequestId();

        String workflowType = AnnotationSupport.workflowTypeName(workflowInterface);
        WorkflowRun started = client.startWorkflow(startOpts, workflowType, arg);
        this.run = started;

        Class<?> returnType = method.getReturnType();
        if (returnType == void.class) {
            started.get(null);
            return null;
        }
        return started.get(returnType);
    }

    private Object query(Method method, Object[] args) throws DataConverterException {
        WorkflowRun r = requireRun("query");
        String queryType = AnnotationSupport.queryTypeName(method);
        Object arg = (args != null && args.length > 0) ? args[0] : null;
        Class<?> returnType = method.getReturnType();
        return client.queryWorkflow(r.workflowId, r.runId, queryType, arg, returnType == void.class ? null : returnType);
    }

    private Object signal(Method method, Object[] args) throws DataConverterException {
        WorkflowRun r = requireRun("signal");
        String signalName = AnnotationSupport.signalName(method);
        Object arg = (args != null && args.length > 0) ? args[0] : null;
        client.signalWorkflow(r.workflowId, r.runId, signalName, arg);
        return null;
    }

    private WorkflowRun requireRun(String verb) {
        WorkflowRun r = run;
        if (r == null) {
            throw new IllegalStateException(
                    "cannot " + verb + " " + workflowInterface.getName()
                            + ": no run started yet through this stub — call the @WorkflowMethod first, "
                            + "or create the stub via WorkflowClient.newWorkflowStub(Class, workflowId, runId) "
                            + "to attach to an existing one");
        }
        return r;
    }
}
