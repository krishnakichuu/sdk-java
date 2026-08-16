package com.flowd.sdk.client;

import com.flowd.api.v1.DescribeWorkflowExecutionResponse;
import com.flowd.api.v1.HistoryEvent;
import com.flowd.api.v1.Payload;
import com.flowd.sdk.internal.converter.DataConverterException;

import java.util.List;

/**
 * WorkflowRun identifies a started execution and lets a caller wait for and
 * unmarshal its result. Mirrors client.go's WorkflowRun exactly: deliberately
 * simple polling (every 200ms against DescribeWorkflow) rather than a
 * streaming/long-poll API, matching the Go SDK's own documented scope.
 */
public final class WorkflowRun {
    private final FlowdClient client;
    public final String workflowId;
    public final String runId;

    WorkflowRun(FlowdClient client, String workflowId, String runId) {
        this.client = client;
        this.workflowId = workflowId;
        this.runId = runId;
    }

    /**
     * Blocks until the run reaches a terminal status, then unmarshals a
     * successful result into resultType (pass null to just wait for
     * completion without reading a result).
     */
    public <T> T get(Class<T> resultType)
            throws InterruptedException, DataConverterException, WorkflowExecutionException {
        while (true) {
            DescribeWorkflowExecutionResponse desc = client.describeWorkflow(workflowId, runId);
            switch (desc.getStatus()) {
                case WORKFLOW_EXECUTION_STATUS_COMPLETED:
                    return fetchResult(resultType);
                case WORKFLOW_EXECUTION_STATUS_FAILED:
                case WORKFLOW_EXECUTION_STATUS_TERMINATED:
                case WORKFLOW_EXECUTION_STATUS_TIMED_OUT:
                    throw new WorkflowExecutionException(
                            "client: workflow did not complete successfully: status " + desc.getStatus());
                default:
                    // RUNNING (or CONTINUED_AS_NEW/UNSPECIFIED) — keep polling.
            }
            Thread.sleep(200);
        }
    }

    private <T> T fetchResult(Class<T> resultType) throws DataConverterException {
        List<HistoryEvent> events = client.getWorkflowHistory(workflowId, runId);
        for (int i = events.size() - 1; i >= 0; i--) {
            HistoryEvent ev = events.get(i);
            if (ev.getAttributesCase() == HistoryEvent.AttributesCase.WORKFLOW_EXECUTION_COMPLETED_EVENT_ATTRIBUTES) {
                if (resultType == null) {
                    return null;
                }
                Payload result = ev.getWorkflowExecutionCompletedEventAttributes().getResult();
                return client.converter.fromPayload(result, resultType);
            }
        }
        throw new IllegalStateException("client: workflow completed but no WorkflowExecutionCompleted event found");
    }
}
