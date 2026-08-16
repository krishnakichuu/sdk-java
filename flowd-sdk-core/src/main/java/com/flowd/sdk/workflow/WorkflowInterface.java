package com.flowd.sdk.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java interface as a flowd workflow definition. The interface must
 * declare exactly one method annotated {@link WorkflowMethod} (its wire
 * {@code workflow_type}), and may additionally declare methods annotated
 * {@link QueryMethod} and/or {@link SignalMethod}.
 *
 * <pre>{@code
 * @WorkflowInterface
 * public interface OrderWorkflow {
 *     @WorkflowMethod
 *     OrderResult processOrder(OrderRequest request);
 *
 *     @QueryMethod
 *     String currentStatus();
 * }
 * }</pre>
 *
 * <p>An implementation class implements this interface directly — there is
 * no base class to extend, no required constructor shape beyond a no-arg
 * constructor (see {@code Worker.registerWorkflowImplementationTypes}) —
 * and calls {@link Workflow}'s static methods for anything requiring the
 * deterministic engine (activities, timers, queries, continue-as-new).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkflowInterface {
}
