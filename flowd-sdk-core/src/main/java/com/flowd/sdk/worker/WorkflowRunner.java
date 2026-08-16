package com.flowd.sdk.worker;

import com.flowd.api.v1.Failure;
import com.flowd.api.v1.Payload;
import com.flowd.sdk.internal.converter.DataConverter;
import com.flowd.sdk.internal.converter.DataConverterException;
import com.flowd.sdk.internal.replayer.Execution;
import com.flowd.sdk.workflow.WorkflowContext;

/**
 * The shared replay/execution primitive behind both
 * {@link FlowdWorker}'s live-task path and {@link ReplayTester}'s
 * standalone replay path. Mirrors sdk/worker/replay.go's runWorkflowFunc
 * (Go) exactly, including which package it lives in: Go keeps this
 * unexported in package {@code worker}, shared by worker.go and replay.go
 * as two files in the same package — this mirrors that with a
 * package-private class shared the same way, so the two callers can never
 * drift into handling a workflow panic vs. a business error differently.
 */
final class WorkflowRunner {
    private WorkflowRunner() {
    }

    static <I, O> void run(Execution exec, WorkflowHandler<I, O> fn, I input, DataConverter converter) {
        exec.dispatcher.go(co -> {
            WorkflowContext wctx = new WorkflowContext(exec, co, converter);
            O out;
            try {
                out = fn.execute(wctx, input);
            } catch (RuntimeException e) {
                // A real panic (including NonDeterministicError) —
                // propagate to Dispatcher.go's own catch(Throwable), NOT a
                // business failure. Do not call exec.complete() here.
                throw e;
            } catch (Exception e) {
                // The workflow function returned a business error (Go's
                // `return zero, err`) — a terminal Failure command, not a
                // coroutine panic.
                exec.complete(null, Failure.newBuilder()
                        .setMessage(e.getMessage() != null ? e.getMessage() : e.toString())
                        .setType(e.getClass().getName())
                        .build());
                return;
            }
            Payload payload;
            try {
                payload = converter.toPayload(out);
            } catch (DataConverterException e) {
                exec.complete(null, Failure.newBuilder().setMessage(e.getMessage()).setType("MarshalError").build());
                return;
            }
            exec.complete(payload, null);
        });
    }
}
