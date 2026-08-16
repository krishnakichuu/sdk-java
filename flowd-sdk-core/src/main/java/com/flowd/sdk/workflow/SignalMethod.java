package com.flowd.sdk.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a {@link WorkflowInterface} as a signal handler: an
 * asynchronous, fire-and-forget notification delivered into a running
 * workflow (see {@code FlowdClient.signalWorkflow} on the sending side).
 *
 * <p>Wired up automatically for every {@code @SignalMethod} on a
 * registered workflow implementation, the same way {@code @QueryMethod}
 * is — see {@code FlowdWorker.registerWorkflowImplementationType}, which
 * binds each one via
 * {@link com.flowd.sdk.workflow.WorkflowContext#setSignalHandler} before
 * the {@code @WorkflowMethod} runs. The underlying engine-level primitive
 * is {@link com.flowd.sdk.internal.replayer.Execution#setSignalHandler},
 * the signal counterpart of {@code setQueryHandler} — see its doc for the
 * full delivery/ordering contract (a signal can be recorded in history
 * before its handler is registered, and is buffered until it is; one
 * recorded on a later task is delivered by a dedicated internal
 * coroutine). The same primitive exists in the Go SDK's replayer
 * (execution.go's {@code SetSignalHandler}), since the replay algorithm
 * must stay identical between the two.
 *
 * <p>A method takes zero or one parameter and returns {@code void} (or is
 * declared to return a value that is simply never produced, since there is
 * no reply channel to send one through — signaling is fire-and-forget). A
 * handler must not block: no activity execution, no sleeping, no
 * ExecuteActivity/Sleep-equivalent call from inside one.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SignalMethod {
    /** The registered signal_name. Defaults to the method's own name when left blank. */
    String name() default "";
}
