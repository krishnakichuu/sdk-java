package com.flowd.sdk.workflow;

import com.flowd.api.v1.Payload;
import com.flowd.sdk.internal.converter.DataConverter;
import com.flowd.sdk.internal.converter.DataConverterException;
import com.flowd.sdk.internal.replayer.ActivityFuture;
import com.flowd.sdk.internal.replayer.Coroutine;
import com.flowd.sdk.internal.replayer.Execution;
import com.flowd.sdk.internal.replayer.SignalHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * WorkflowContext is passed to every workflow function and coroutine
 * started with {@link #go}. Mirrors sdk/workflow/context.go's Context
 * (Go): the restricted, deterministic surface workflow code is allowed to
 * use — no direct I/O, no wall-clock time, no raw threads/goroutines;
 * anything a workflow needs from the outside world goes through one of
 * these methods.
 *
 * <p>Unlike Go, which exposes these as free functions taking Context as
 * the first argument ({@code workflow.Now(ctx)}, {@code
 * workflow.Sleep(ctx, d)}, ...), they're instance methods here — the more
 * natural Java calling convention for the exact same primitives and the
 * exact same restriction.
 *
 * <p>Exported for {@link com.flowd.sdk.worker.FlowdWorker}'s use when
 * driving a workflow task; workflow authors never construct this
 * themselves — the SDK constructs it for them, same split as Go's
 * {@code workflow.NewContext} doc comment describes.
 */
public final class WorkflowContext {
    private final Execution exec;
    private final Coroutine co;
    private final DataConverter converter;

    public WorkflowContext(Execution exec, Coroutine co, DataConverter converter) {
        this.exec = exec;
        this.co = co;
        this.converter = converter;
    }

    /**
     * The time the current workflow task started, recorded in history —
     * never wall-clock — so replay reconstructs the exact same value every
     * time.
     */
    public Instant now() {
        return exec.now;
    }

    /**
     * Blocks the calling coroutine until d has elapsed according to the
     * workflow's timeline (a server-fired timer), not wall-clock time.
     */
    public void sleep(Duration d) {
        exec.scheduleTimer(d).get(co);
    }

    /** Schedules activityType with the default ActivityOptions. */
    public ActivityInvocation executeActivity(String activityType, Object input) {
        return executeActivity(activityType, input, ActivityOptions.DEFAULT);
    }

    /**
     * Schedules activityType with input; the returned
     * {@link ActivityInvocation} blocks on {@code get} until the result is
     * known.
     */
    public ActivityInvocation executeActivity(String activityType, Object input, ActivityOptions opts) {
        Payload payload;
        try {
            payload = converter.toPayload(input);
        } catch (DataConverterException e) {
            // Marshaling the *input* to an activity call is a programmer
            // error (a value that doesn't serialize), not a runtime
            // condition workflow code should have to handle explicitly —
            // same treatment Go gives an equivalent failure via panic.
            throw new IllegalArgumentException("workflow: failed to marshal activity input", e);
        }
        ActivityFuture future = exec.scheduleActivity(activityType, payload, (opts != null ? opts : ActivityOptions.DEFAULT).toInternal());
        return new ActivityInvocation(future, co, converter);
    }

    /**
     * Starts a new cooperatively-scheduled coroutine. Unlike a raw thread,
     * its scheduling is deterministic across replay because it only ever
     * runs to completion or to its next blocking point when the dispatcher
     * resumes it.
     */
    public void go(Consumer<WorkflowContext> fn) {
        exec.dispatcher.go(childCo -> fn.accept(new WorkflowContext(exec, childCo, converter)));
    }

    /**
     * Registers handler to answer queries of queryType: argType marshals
     * the query's argument (pass {@code null} if the query takes none),
     * and handler's return value is marshaled back via the same
     * DataConverter. Typically called once, near the top of a workflow
     * function/constructor, before its first blocking primitive — see
     * {@link Execution#setQueryHandler}'s doc for why that ordering
     * matters. The annotation-based API ({@code FlowdWorker.registerWorkflowImplementationTypes})
     * calls this automatically for every {@code @QueryMethod} it finds;
     * call it directly only when using the lower-level, non-annotation API.
     *
     * <p>Not generic over the argument/result types: a reflection-driven
     * caller (the annotation-based adapter above) has no compile-time type
     * to give it, only a runtime {@code Class<?>} — so this takes {@code
     * Object} on both sides. A direct caller that wants a typed handler
     * can simply cast inside the lambda body.
     */
    public void setQueryHandler(String queryType, Class<?> argType, Function<Object, Object> handler) {
        exec.setQueryHandler(queryType, args -> {
            Object arg = argType == null ? null : converter.fromPayload(args, argType);
            Object result = handler.apply(arg);
            return converter.toPayload(result);
        });
    }

    /**
     * Registers handler to act on signals of signalName: argType marshals
     * the signal's argument (pass {@code null} if the signal carries none)
     * — see {@link Execution#setSignalHandler}'s doc for the full
     * delivery/ordering contract and why it must not block (no
     * {@code executeActivity}/{@code sleep}/anything else that yields from
     * inside one). Typically called once, near the top of a workflow
     * function/constructor, before its first blocking primitive — same
     * reasoning as {@link #setQueryHandler}. The annotation-based API
     * ({@code FlowdWorker.registerWorkflowImplementationTypes}) calls this
     * automatically for every {@code @SignalMethod} it finds; call it
     * directly only when using the lower-level, non-annotation API.
     *
     * <p>Unlike {@link #setQueryHandler}, handler has no return value and
     * {@link SignalHandler} declares no checked exception: signaling is
     * fire-and-forget, with no reply channel to report a failure through
     * the way a query's answer would — a malformed payload is a real bug
     * (this workflow and whoever sent the signal disagree on its shape),
     * so it fails loudly as an unchecked exception instead.
     */
    public void setSignalHandler(String signalName, Class<?> argType, Consumer<Object> handler) {
        exec.setSignalHandler(signalName, args -> {
            Object arg;
            try {
                arg = argType == null ? null : converter.fromPayload(args, argType);
            } catch (DataConverterException e) {
                throw new IllegalArgumentException("workflow: signal \"" + signalName + "\": unmarshal args", e);
            }
            handler.accept(arg);
        });
    }

    /**
     * Closes this run and atomically starts a fresh run of workflowType
     * under the same workflow_id, instead of this workflow function
     * returning normally — the way to write a workflow that legitimately
     * loops forever without its history growing without bound. Must be
     * the last thing the calling coroutine does: the worker driving this
     * task detects a pending continue-as-new once the round ends and
     * reports it instead of a normal completion, so any code after this
     * call still runs (same as Go workflow code returning immediately
     * after constructing its ContinueAsNewError) but has no effect on the
     * outcome.
     */
    public void continueAsNew(String workflowType, Object input, ContinueAsNewOptions options) {
        Payload payload;
        try {
            payload = converter.toPayload(input);
        } catch (DataConverterException e) {
            throw new IllegalArgumentException("workflow: failed to marshal continue-as-new input", e);
        }
        exec.continueAsNew(workflowType, payload, (options != null ? options : ContinueAsNewOptions.DEFAULT).toInternal());
    }
}
