package com.flowd.sdk.worker;

import com.flowd.api.v1.HistoryEvent;
import com.flowd.api.v1.Payload;
import com.flowd.sdk.internal.converter.DataConverter;
import com.flowd.sdk.internal.converter.DataConverterException;
import com.flowd.sdk.internal.replayer.Execution;
import com.flowd.sdk.internal.replayer.NonDeterministicError;

import java.util.List;

/**
 * Drives workflowFn — a {@link WorkflowHandler} — against a fixed history
 * using the exact same deterministic replay algorithm a live worker uses,
 * with no server or task queue involved. Mirrors sdk/worker/replay.go's
 * ReplayWorkflowHistory/IsNonDeterministic (Go): the SDK's golden-replay
 * testing primitive — capture a real run's history once, check it in, and
 * assert replaying it against current workflow code still produces the
 * same result. The earliest signal that a workflow code change breaks
 * replay of an already-running execution.
 */
public final class ReplayTester {
    private ReplayTester() {
    }

    /** Reports whether err (from {@link #replay}) is a replay
     *  non-determinism mismatch rather than an ordinary workflow failure. */
    public static boolean isNonDeterministic(Throwable err) {
        return err instanceof NonDeterministicError;
    }

    public static <I, O> Payload replay(List<HistoryEvent> events, Class<I> inputType,
                                         WorkflowHandler<I, O> workflowFn, DataConverter converter) throws Exception {
        Execution exec = new Execution();
        Execution.LoadedHistory loaded = exec.loadHistory(events);

        I input;
        try {
            input = converter.fromPayload(loaded.input(), inputType);
        } catch (DataConverterException e) {
            throw new IllegalStateException("input unmarshal: " + e.getMessage(), e);
        }

        WorkflowRunner.run(exec, workflowFn, input, converter);
        exec.dispatcher.executeRound();

        Throwable panic = exec.dispatcher.firstPanic();
        if (panic != null) {
            if (panic instanceof RuntimeException re) {
                throw re;
            }
            throw new Exception(panic);
        }
        if (exec.err != null) {
            throw new Exception("workflow failed: " + exec.err.getMessage());
        }
        return exec.result;
    }
}
