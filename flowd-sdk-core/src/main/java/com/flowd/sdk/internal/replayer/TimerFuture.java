package com.flowd.sdk.internal.replayer;

/** Resolves once its timer has fired. Mirrors execution.go's TimerFuture (Go). */
public final class TimerFuture {
    private final Execution exec;
    public final long timerId;

    TimerFuture(Execution exec, long timerId) {
        this.exec = exec;
        this.timerId = timerId;
    }

    public void get(Coroutine co) {
        exec.timerFutureGet(co, timerId);
    }
}
