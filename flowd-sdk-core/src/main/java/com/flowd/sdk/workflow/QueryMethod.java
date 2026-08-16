package com.flowd.sdk.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a {@link WorkflowInterface} as a query handler: a
 * synchronous, read-only, non-history-mutating call answered from the
 * workflow's current in-memory state (see {@code Workflow.setQueryHandler},
 * which the annotation-based worker calls on the implementation's behalf).
 *
 * <p>Query methods take zero or one parameter and must return a value
 * (never {@code void}) — the whole point of a query is to read something
 * out. They must be pure: no activity execution, no sleeping, no mutating
 * fields other code depends on for correctness, since a query can be
 * answered concurrently with the workflow's own progress from the same
 * captured state.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface QueryMethod {
    /** The registered query_type. Defaults to the method's own name when left blank. */
    String name() default "";
}
