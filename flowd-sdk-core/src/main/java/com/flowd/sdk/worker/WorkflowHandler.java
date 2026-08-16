package com.flowd.sdk.worker;

import com.flowd.sdk.workflow.WorkflowContext;

/**
 * A registered workflow implementation. The checked {@code throws Exception}
 * is deliberate: it's how a workflow reports an ordinary business failure
 * (mirrors Go's {@code return zero, err} — becomes a terminal
 * WorkflowExecutionFailed command). An unchecked {@link RuntimeException}
 * thrown instead (including {@code NonDeterministicError}) is treated as a
 * real panic and is NOT caught here — see FlowdWorker.processWorkflowTask,
 * which deliberately only catches checked exceptions around this call so
 * RuntimeExceptions propagate up to the Dispatcher's own panic handling,
 * exactly mirroring how Go distinguishes an returned {@code error} from an
 * actual {@code panic}.
 */
@FunctionalInterface
public interface WorkflowHandler<I, O> {
    O execute(WorkflowContext ctx, I input) throws Exception;
}
