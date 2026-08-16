package com.flowd.sdk.internal.replayer;

import com.flowd.api.v1.RetryPolicy;

import java.time.Duration;

/** Configures a single scheduleActivity call. Mirrors execution.go's ActivityOptions (Go). */
public record ActivityOptions(RetryPolicy retryPolicy, Duration scheduleToStartTimeout, Duration startToCloseTimeout) {
    public static final ActivityOptions DEFAULT = new ActivityOptions(null, null, null);
}
