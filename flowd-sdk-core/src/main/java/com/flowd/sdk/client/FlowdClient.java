package com.flowd.sdk.client;

import com.flowd.api.v1.DescribeWorkflowExecutionRequest;
import com.flowd.api.v1.DescribeWorkflowExecutionResponse;
import com.flowd.api.v1.GetWorkflowExecutionHistoryRequest;
import com.flowd.api.v1.GetWorkflowExecutionHistoryResponse;
import com.flowd.api.v1.HistoryEvent;
import com.flowd.api.v1.Payload;
import com.flowd.api.v1.QueryWorkflowExecutionRequest;
import com.flowd.api.v1.QueryWorkflowExecutionResponse;
import com.flowd.api.v1.RetryPolicy;
import com.flowd.api.v1.SignalWorkflowExecutionRequest;
import com.flowd.api.v1.StartWorkflowExecutionRequest;
import com.flowd.api.v1.StartWorkflowExecutionResponse;
import com.flowd.api.v1.TerminateWorkflowExecutionRequest;
import com.flowd.api.v1.WorkflowServiceGrpc;
import com.flowd.sdk.internal.converter.DataConverter;
import com.flowd.sdk.internal.converter.DataConverterException;
import com.flowd.sdk.internal.converter.JsonDataConverter;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FlowdClient is the Java equivalent of sdk/client (Go): start, signal,
 * describe, terminate, and read the history of flowd workflow executions —
 * the "producer" side. See {@link com.flowd.sdk.worker.FlowdWorker} for the
 * poll/respond ("consumer") side, kept as a separate concern exactly as in
 * Go, where sdk/client and sdk/worker are distinct packages.
 *
 * <p>Unlike Go's {@code StartWorkflow}, which derives a workflow's
 * registered type name from a function reference via reflection
 * ({@code funcname.Of}), Java has no equivalent way to get a stable name
 * from a lambda or method reference — so {@link #startWorkflow} always
 * takes the workflow type name explicitly (Go's {@code StartWorkflowByType}
 * shape). {@link com.flowd.sdk.worker.FlowdWorker#registerWorkflow} takes
 * the same explicit-name approach for the same reason.
 */
public final class FlowdClient implements AutoCloseable {

    private static final String DEFAULT_NAMESPACE = "default";

    private final ManagedChannel channel;
    private final WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
    private final String namespace;

    /** Package-private: FlowdWorker and WorkflowRun read this directly, matching how
     *  Go's worker.go pulls the DataConverter out of the same client it was given. */
    final DataConverter converter;

    private FlowdClient(ManagedChannel channel, String namespace, DataConverter converter) {
        this.channel = channel;
        this.namespace = namespace;
        this.converter = converter;
        this.blockingStub = WorkflowServiceGrpc.newBlockingStub(channel);
    }

    public static FlowdClient dial(String target) {
        return dial(target, new Options());
    }

    public static FlowdClient dial(String target, Options options) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();
        String namespace = (options.namespace == null || options.namespace.isEmpty())
                ? DEFAULT_NAMESPACE
                : options.namespace;
        DataConverter converter = options.converter != null ? options.converter : JsonDataConverter.INSTANCE;
        return new FlowdClient(channel, namespace, converter);
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Exposes the raw generated gRPC stub, for FlowdWorker's use polling task queues —
     *  application code should use the higher-level methods below instead, same split
     *  as Go's {@code Client.WorkflowService()} doc comment describes. */
    public WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowService() {
        return blockingStub;
    }

    public String namespace() {
        return namespace;
    }

    public static final class Options {
        public String namespace;
        public DataConverter converter;
    }

    public static final class StartWorkflowOptions {
        public String id;
        public String taskQueue;
        public java.time.Duration workflowExecutionTimeout;
        public java.time.Duration workflowRunTimeout;
        public java.time.Duration workflowTaskTimeout;
        public RetryPolicy retryPolicy;
        /** Idempotency key: a repeated start with the same (namespace, workflowId,
         *  requestId) returns the existing run instead of erroring or starting a
         *  duplicate. Left blank, the server generates one — meaning repeated calls
         *  are NOT automatically idempotent unless the caller supplies a stable id. */
        public String requestId;
    }

    /** Starts workflowType with a single input argument. */
    public WorkflowRun startWorkflow(StartWorkflowOptions opts, String workflowType, Object arg)
            throws DataConverterException {
        Payload payload = converter.toPayload(arg);
        StartWorkflowExecutionRequest.Builder req = StartWorkflowExecutionRequest.newBuilder()
                .setNamespace(namespace)
                .setWorkflowId(opts.id)
                .setWorkflowType(workflowType)
                .setTaskQueue(opts.taskQueue)
                .setInput(payload)
                .setRequestId(opts.requestId != null ? opts.requestId : "");
        if (opts.workflowExecutionTimeout != null) {
            req.setWorkflowExecutionTimeout(toProtoDuration(opts.workflowExecutionTimeout));
        }
        if (opts.workflowRunTimeout != null) {
            req.setWorkflowRunTimeout(toProtoDuration(opts.workflowRunTimeout));
        }
        if (opts.workflowTaskTimeout != null) {
            req.setWorkflowTaskTimeout(toProtoDuration(opts.workflowTaskTimeout));
        }
        if (opts.retryPolicy != null) {
            req.setRetryPolicy(opts.retryPolicy);
        }

        StartWorkflowExecutionResponse resp = blockingStub.startWorkflowExecution(req.build());
        return new WorkflowRun(this, opts.id, resp.getRunId());
    }

    public void signalWorkflow(String workflowId, String runId, String signalName, Object arg)
            throws DataConverterException {
        Payload payload = converter.toPayload(arg);
        blockingStub.signalWorkflowExecution(SignalWorkflowExecutionRequest.newBuilder()
                .setNamespace(namespace)
                .setWorkflowId(workflowId)
                .setRunId(runId != null ? runId : "")
                .setSignalName(signalName)
                .setInput(payload)
                .build());
    }

    /**
     * Synchronously asks a running (or completed) execution's query_type
     * handler — registered in workflow code via {@code
     * WorkflowContext.setQueryHandler}/{@code Workflow.setQueryHandler} —
     * for its current answer, and unmarshals the result into resultType
     * (pass null to ignore the result). Unlike signalWorkflow, this blocks
     * until a worker actually answers or the server's own bounded wait
     * gives up; it never appends anything to the workflow's history.
     * Mirrors client.go's Client.QueryWorkflow (Go).
     */
    public <T> T queryWorkflow(String workflowId, String runId, String queryType, Object arg, Class<T> resultType)
            throws DataConverterException {
        Payload payload = converter.toPayload(arg);
        QueryWorkflowExecutionResponse resp = blockingStub.queryWorkflowExecution(
                QueryWorkflowExecutionRequest.newBuilder()
                        .setNamespace(namespace)
                        .setWorkflowId(workflowId)
                        .setRunId(runId != null ? runId : "")
                        .setQueryType(queryType)
                        .setQueryArgs(payload)
                        .build());
        if (resultType == null) {
            return null;
        }
        return converter.fromPayload(resp.getResult(), resultType);
    }

    public DescribeWorkflowExecutionResponse describeWorkflow(String workflowId, String runId) {
        return blockingStub.describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(namespace)
                .setWorkflowId(workflowId)
                .setRunId(runId != null ? runId : "")
                .build());
    }

    /** Paginates via next_page_token, same as Go's GetWorkflowHistory. */
    public List<HistoryEvent> getWorkflowHistory(String workflowId, String runId) {
        List<HistoryEvent> events = new ArrayList<>();
        ByteString token = ByteString.EMPTY;
        while (true) {
            GetWorkflowExecutionHistoryResponse resp = blockingStub.getWorkflowExecutionHistory(
                    GetWorkflowExecutionHistoryRequest.newBuilder()
                            .setNamespace(namespace)
                            .setWorkflowId(workflowId)
                            .setRunId(runId != null ? runId : "")
                            .setNextPageToken(token)
                            .build());
            events.addAll(resp.getEventsList());
            if (resp.getNextPageToken().isEmpty()) {
                return events;
            }
            token = resp.getNextPageToken();
        }
    }

    public void terminateWorkflow(String workflowId, String runId, String reason) {
        blockingStub.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.newBuilder()
                .setNamespace(namespace)
                .setWorkflowId(workflowId)
                .setRunId(runId != null ? runId : "")
                .setReason(reason)
                .build());
    }

    private static com.google.protobuf.Duration toProtoDuration(java.time.Duration d) {
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(d.getSeconds())
                .setNanos(d.getNano())
                .build();
    }
}
