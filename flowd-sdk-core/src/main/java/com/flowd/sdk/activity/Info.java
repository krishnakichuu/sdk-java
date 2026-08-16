package com.flowd.sdk.activity;

/** Info about the currently-executing activity task. Mirrors activity/context.go's Info (Go). */
public record Info(long activityId, String activityType, int attempt) {
}
