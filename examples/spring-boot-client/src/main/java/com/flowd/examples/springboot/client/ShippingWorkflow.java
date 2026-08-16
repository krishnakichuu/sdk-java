package com.flowd.examples.springboot.client;

import com.flowd.sdk.workflow.WorkflowInterface;
import com.flowd.sdk.workflow.WorkflowMethod;

/**
 * The client-side half of the workflow contract — deliberately just the
 * interface, no implementation, mirroring how a real client application
 * only ever depends on a workflow's shape, never its logic. The wire
 * workflow_type ("ShippingWorkflow", the interface's simple name — see
 * {@code AnnotationSupport.workflowTypeName}) is what actually links this
 * to {@code examples.springboot.worker.ShippingWorkflowImpl}, not the
 * Java type itself; the two modules never share a classloader.
 */
@WorkflowInterface
public interface ShippingWorkflow {
    @WorkflowMethod
    String ship(String orderId);
}
