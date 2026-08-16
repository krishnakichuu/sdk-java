package com.flowd.examples.orderprocessing;

import com.flowd.sdk.workflow.QueryMethod;
import com.flowd.sdk.workflow.WorkflowInterface;
import com.flowd.sdk.workflow.WorkflowMethod;

/**
 * The annotation-based counterpart to {@code helloworkflow}'s {@code
 * SimpleWorkflow}: same replay engine underneath, but defined as a
 * Temporal-style interface + implementation instead of a bare function
 * reference, with a query handler exposing in-progress state.
 */
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    String processOrder(String orderId);

    @QueryMethod
    String currentStatus();
}
