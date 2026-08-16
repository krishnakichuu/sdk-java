package com.flowd.examples.springboot.worker;

import com.flowd.sdk.workflow.WorkflowInterface;
import com.flowd.sdk.workflow.WorkflowMethod;

@WorkflowInterface
public interface ShippingWorkflow {
    @WorkflowMethod
    String ship(String orderId);
}
