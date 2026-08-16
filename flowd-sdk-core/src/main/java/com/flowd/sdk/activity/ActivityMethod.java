package com.flowd.sdk.activity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the registered {@code activity_type} of one method of an
 * {@link ActivityInterface}. Optional: an unannotated method registers
 * under its own method name, matching Go's default (a function's own name).
 * A method may take any number of parameters, same single-Payload
 * (or positional-array, for more than one) convention as
 * {@link com.flowd.sdk.workflow.WorkflowMethod}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActivityMethod {
    String name() default "";
}
