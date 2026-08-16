package com.flowd.sdk.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the single entry-point method of a {@link WorkflowInterface}. Its
 * {@link #name} becomes the wire {@code workflow_type} used to start,
 * dispatch, and register the workflow — the same identifier a Go worker
 * would register under {@code worker.RegisterWorkflow}, so a Go and Java
 * worker can serve the same workflow type interchangeably as long as both
 * agree on this name.
 *
 * <p>The method's parameters (its input) are marshaled through the
 * configured DataConverter into a single {@code Payload} — this matches
 * flowd's wire contract, where {@code StartWorkflowExecutionRequest.input}
 * is a single value, not an argument list. Zero or one parameter is
 * encoded as that value directly (or nothing); more than one is packed
 * into a single positional JSON array, decoded back by matching formal
 * parameter type (see {@code MethodArguments}) — so a 0/1-parameter method
 * predating multi-argument support serializes exactly as it always has.
 * May declare any return type, including {@code void}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkflowMethod {
    /**
     * The registered workflow_type. Defaults to the declaring interface's
     * simple name (e.g. {@code OrderWorkflow}) when left blank — mirroring
     * how a Go workflow function's registered name defaults to its own
     * function name.
     */
    String name() default "";
}
