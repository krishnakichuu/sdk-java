package com.flowd.sdk.workflow;

/** Wraps a terminal activity failure surfaced to workflow code via {@link ActivityInvocation#get}.
 *  Mirrors workflow/context.go's ActivityError (Go). */
public final class ActivityException extends RuntimeException {
    public final String type;

    public ActivityException(String type, String message) {
        super("activity error (" + type + "): " + message);
        this.type = type;
    }
}
