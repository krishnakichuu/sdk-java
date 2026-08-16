package com.flowd.sdk.client;

import com.flowd.sdk.internal.registry.AnnotationSupport;

import java.lang.reflect.Proxy;

/**
 * The annotation-based, type-safe client API: wraps a {@link FlowdClient}
 * connection and hands out proxies implementing an application's own
 * {@code @WorkflowInterface} — calling the proxy's {@code @WorkflowMethod}
 * starts the workflow and blocks for its typed result, calling a
 * {@code @QueryMethod}/{@code @SignalMethod} queries/signals the run the
 * stub is bound to.
 *
 * <pre>{@code
 * FlowdClient conn = FlowdClient.dial("localhost:7233");
 * WorkflowClient client = WorkflowClient.newInstance(conn);
 *
 * OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
 *     WorkflowOptions.newBuilder()
 *         .setWorkflowId("order-4521")
 *         .setTaskQueue("orders")
 *         .build());
 *
 * OrderResult result = wf.processOrder(request); // starts + blocks for result, typed
 * }</pre>
 *
 * <p>This is a layer over {@link FlowdClient}, not a replacement for it —
 * {@link #raw()} exposes the underlying connection for anything not yet
 * expressible through a typed stub (namespace management, terminate,
 * history inspection). A single FlowdClient/WorkflowClient pair is safe to
 * share across many stubs and threads.
 */
public final class WorkflowClient {
    private final FlowdClient flowdClient;

    private WorkflowClient(FlowdClient flowdClient) {
        this.flowdClient = flowdClient;
    }

    public static WorkflowClient newInstance(FlowdClient flowdClient) {
        return new WorkflowClient(flowdClient);
    }

    /** The underlying connection, for calls not yet exposed through a typed stub. */
    public FlowdClient raw() {
        return flowdClient;
    }

    /**
     * Returns a stub that starts a new run of workflowInterface's
     * registered workflow_type (see {@code @WorkflowMethod}) the first
     * time its @WorkflowMethod is invoked, per options.
     */
    @SuppressWarnings("unchecked")
    public <T> T newWorkflowStub(Class<T> workflowInterface, WorkflowOptions options) {
        AnnotationSupport.requireWorkflowMethod(workflowInterface); // validates eagerly, at stub-creation time
        return (T) Proxy.newProxyInstance(
                workflowInterface.getClassLoader(),
                new Class<?>[]{workflowInterface},
                new WorkflowStubInvocationHandler(workflowInterface, options, flowdClient));
    }

    /**
     * Returns a stub attached to an already-started run — for
     * querying/signaling a workflow started elsewhere (a different
     * process, an earlier stub, {@code flow-cli}, ...) without starting a
     * new one. Calling the interface's @WorkflowMethod through a stub
     * created this way throws {@link IllegalStateException}: use {@link
     * #newWorkflowStub(Class, WorkflowOptions)} to start a run.
     */
    @SuppressWarnings("unchecked")
    public <T> T newWorkflowStub(Class<T> workflowInterface, String workflowId, String runId) {
        AnnotationSupport.requireWorkflowMethod(workflowInterface);
        return (T) Proxy.newProxyInstance(
                workflowInterface.getClassLoader(),
                new Class<?>[]{workflowInterface},
                new WorkflowStubInvocationHandler(workflowInterface, workflowId, runId, flowdClient));
    }

    public <T> T newWorkflowStub(Class<T> workflowInterface, String workflowId) {
        return newWorkflowStub(workflowInterface, workflowId, null);
    }
}
