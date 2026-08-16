package com.flowd.sdk.client;

/** Thrown by {@link WorkflowRun#get} when a run reaches a non-successful terminal status. */
public class WorkflowExecutionException extends Exception {
    public WorkflowExecutionException(String message) {
        super(message);
    }
}
