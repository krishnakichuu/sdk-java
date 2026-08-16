package com.flowd.sdk.internal.replayer;

import com.flowd.api.v1.ActivityTaskCompletedEventAttributes;
import com.flowd.api.v1.ActivityTaskFailedEventAttributes;
import com.flowd.api.v1.ActivityTaskScheduledEventAttributes;
import com.flowd.api.v1.Command;
import com.flowd.api.v1.CompleteWorkflowExecutionCommand;
import com.flowd.api.v1.ContinueAsNewWorkflowExecutionCommand;
import com.flowd.api.v1.FailWorkflowExecutionCommand;
import com.flowd.api.v1.Failure;
import com.flowd.api.v1.HistoryEvent;
import com.flowd.api.v1.Payload;
import com.flowd.api.v1.RetryPolicy;
import com.flowd.api.v1.ScheduleActivityTaskCommand;
import com.flowd.api.v1.StartTimerCommand;
import com.flowd.api.v1.TimerStartedEventAttributes;
import com.flowd.api.v1.WorkflowExecutionSignaledEventAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-workflow-task state shared by all of a workflow's coroutines. Mirrors
 * sdk/internal/replayer/execution.go's Execution (Go): separates what
 * history already recorded (already-scheduled activities/timers and their
 * outcomes) from what workflow code newly schedules during this task's
 * single round — only the latter becomes a Command sent back to the
 * server.
 */
public final class Execution {
    public final Dispatcher dispatcher = new Dispatcher();

    public volatile Instant now;

    private final List<ScheduledActivity> scheduledActivities = new ArrayList<>();
    private final List<ScheduledTimer> scheduledTimers = new ArrayList<>();
    private final Map<Long, ActivityOutcome> activityOutcomes = new HashMap<>();
    private final Set<Long> firedTimers = new HashSet<>();
    private final List<PendingSignal> pendingSignals = new ArrayList<>();

    private long nextActivityIdx = 0;
    private long nextTimerIdx = 0;

    public final List<Command> newCommands = new ArrayList<>();

    public volatile Payload result;
    public volatile Failure err;

    private record ScheduledActivity(long activityId, String activityType) {
    }

    private record ScheduledTimer(long timerId) {
    }

    public record LoadedHistory(Payload input, String workflowType) {
    }

    /**
     * Scans a workflow run's full history (always replayed from the
     * beginning in this first pass — sticky caching is a Phase 2 item, same
     * as Go) and returns the original start input/workflow type, populating
     * the already-scheduled activity/timer state used by
     * {@link #scheduleActivity} / {@link #scheduleTimer} to detect
     * non-determinism and avoid re-emitting commands for already-recorded
     * work.
     */
    public LoadedHistory loadHistory(List<HistoryEvent> events) {
        Payload input = null;
        String workflowType = null;
        for (HistoryEvent ev : events) {
            switch (ev.getAttributesCase()) {
                case WORKFLOW_EXECUTION_STARTED_EVENT_ATTRIBUTES -> {
                    input = ev.getWorkflowExecutionStartedEventAttributes().getInput();
                    workflowType = ev.getWorkflowExecutionStartedEventAttributes().getWorkflowType();
                }
                case WORKFLOW_TASK_STARTED_EVENT_ATTRIBUTES ->
                        this.now = Instant.ofEpochSecond(ev.getEventTime().getSeconds(), ev.getEventTime().getNanos());
                case ACTIVITY_TASK_SCHEDULED_EVENT_ATTRIBUTES -> {
                    ActivityTaskScheduledEventAttributes at = ev.getActivityTaskScheduledEventAttributes();
                    scheduledActivities.add(new ScheduledActivity(at.getActivityId(), at.getActivityType()));
                }
                case ACTIVITY_TASK_COMPLETED_EVENT_ATTRIBUTES -> {
                    ActivityTaskCompletedEventAttributes ac = ev.getActivityTaskCompletedEventAttributes();
                    activityOutcomes.put(ac.getActivityId(), ActivityOutcome.ofResult(ac.getResult()));
                }
                case ACTIVITY_TASK_FAILED_EVENT_ATTRIBUTES -> {
                    ActivityTaskFailedEventAttributes af = ev.getActivityTaskFailedEventAttributes();
                    activityOutcomes.put(af.getActivityId(), ActivityOutcome.ofFailure(af.getFailure()));
                }
                case TIMER_STARTED_EVENT_ATTRIBUTES -> {
                    TimerStartedEventAttributes ts = ev.getTimerStartedEventAttributes();
                    scheduledTimers.add(new ScheduledTimer(ts.getTimerId()));
                }
                case TIMER_FIRED_EVENT_ATTRIBUTES ->
                        firedTimers.add(ev.getTimerFiredEventAttributes().getTimerId());
                case WORKFLOW_EXECUTION_SIGNALED_EVENT_ATTRIBUTES -> {
                    WorkflowExecutionSignaledEventAttributes sig = ev.getWorkflowExecutionSignaledEventAttributes();
                    pendingSignals.add(new PendingSignal(sig.getSignalName(), sig.getInput()));
                }
                default -> {
                    // WORKFLOW_TASK_SCHEDULED/COMPLETED/FAILED and the
                    // workflow-execution-level terminal events carry no
                    // state the dispatcher needs to reconstruct.
                }
            }
        }
        return new LoadedHistory(input, workflowType);
    }

    ActivityOutcome activityFutureGet(Coroutine co, long activityId) {
        while (true) {
            ActivityOutcome outcome = activityOutcomes.get(activityId);
            if (outcome != null) {
                return outcome;
            }
            co.yield();
        }
    }

    void timerFutureGet(Coroutine co, long timerId) {
        while (!firedTimers.contains(timerId)) {
            co.yield();
        }
    }

    /**
     * The engine-level primitive behind {@code WorkflowContext.executeActivity}.
     * Activity IDs are assigned purely by call order (not server-assigned),
     * which is what makes them reproducible across replay: the Nth
     * scheduleActivity call always gets id N+1, whether this is the
     * original execution or a replay.
     */
    public synchronized ActivityFuture scheduleActivity(String activityType, Payload input, ActivityOptions opts) {
        long idx = nextActivityIdx++;
        long id = idx + 1;

        if (idx < scheduledActivities.size()) {
            ScheduledActivity recorded = scheduledActivities.get((int) idx);
            if (recorded.activityId() != id || !recorded.activityType().equals(activityType)) {
                throw new NonDeterministicError(String.format(
                        "activity #%d: workflow now schedules type %s but history recorded id=%d type %s",
                        idx + 1, activityType, recorded.activityId(), recorded.activityType()));
            }
            return new ActivityFuture(this, id);
        }

        ScheduleActivityTaskCommand.Builder cmd = ScheduleActivityTaskCommand.newBuilder()
                .setActivityId(id)
                .setActivityType(activityType)
                .setInput(input != null ? input : Payload.getDefaultInstance());
        if (opts != null && opts.retryPolicy() != null) {
            cmd.setRetryPolicy(opts.retryPolicy());
        }
        if (opts != null && opts.scheduleToStartTimeout() != null) {
            cmd.setScheduleToStartTimeout(toProtoDuration(opts.scheduleToStartTimeout()));
        }
        if (opts != null && opts.startToCloseTimeout() != null) {
            cmd.setStartToCloseTimeout(toProtoDuration(opts.startToCloseTimeout()));
        }
        newCommands.add(Command.newBuilder().setScheduleActivityTask(cmd).build());
        return new ActivityFuture(this, id);
    }

    /** Same call-order id assignment and non-determinism check as {@link #scheduleActivity}. */
    public synchronized TimerFuture scheduleTimer(Duration duration) {
        long idx = nextTimerIdx++;
        long id = idx + 1;

        if (idx < scheduledTimers.size()) {
            ScheduledTimer recorded = scheduledTimers.get((int) idx);
            if (recorded.timerId() != id) {
                throw new NonDeterministicError(String.format(
                        "timer #%d: workflow now starts a timer but history recorded a different timer id=%d",
                        idx + 1, recorded.timerId()));
            }
            return new TimerFuture(this, id);
        }

        newCommands.add(Command.newBuilder().setStartTimer(
                StartTimerCommand.newBuilder()
                        .setTimerId(id)
                        .setDuration(toProtoDuration(duration))
        ).build());
        return new TimerFuture(this, id);
    }

    /** Records the workflow's terminal outcome as a command; called once the
     *  workflow function returns within a round. */
    public void complete(Payload result, Failure failure) {
        if (failure != null) {
            this.err = failure;
            newCommands.add(Command.newBuilder().setFailWorkflowExecution(
                    FailWorkflowExecutionCommand.newBuilder().setFailure(failure)
            ).build());
            return;
        }
        this.result = result;
        newCommands.add(Command.newBuilder().setCompleteWorkflowExecution(
                CompleteWorkflowExecutionCommand.newBuilder()
                        .setResult(result != null ? result : Payload.getDefaultInstance())
        ).build());
    }

    // ---- Continue-as-new ----

    /**
     * Set instead of {@link #result}/{@link #err} when the workflow asked
     * to continue as new rather than complete or fail — see
     * {@link #continueAsNew}. A caller (FlowdWorker, ReplayTester) checks
     * this to distinguish "returned normally" from "asked for a fresh run"
     * once the round is done. Mirrors execution.go's Execution.ContinuedAsNew (Go).
     */
    public volatile ContinueAsNewWorkflowExecutionCommand continuedAsNew;

    /** A zero-value (all-null) instance means "same as the current run" for every field. */
    public record ContinueAsNewOptions(String taskQueue, RetryPolicy retryPolicy,
                                        Duration workflowRunTimeout, Duration workflowTaskTimeout) {
        public static final ContinueAsNewOptions DEFAULT = new ContinueAsNewOptions(null, null, null, null);
    }

    /**
     * Records a continue-as-new command in place of {@link #complete}: this
     * run closes and a fresh run of workflowType starts under the same
     * workflow_id. Like complete, called once the workflow function returns
     * within a round — the caller is responsible for detecting a
     * continue-as-new request and routing here instead of complete, since
     * Execution has no visibility into workflow-level exception types.
     * Mirrors execution.go's Execution.ContinueAsNew (Go).
     */
    public void continueAsNew(String workflowType, Payload input, ContinueAsNewOptions opts) {
        ContinueAsNewWorkflowExecutionCommand.Builder cmd = ContinueAsNewWorkflowExecutionCommand.newBuilder()
                .setWorkflowType(workflowType)
                .setInput(input != null ? input : Payload.getDefaultInstance());
        if (opts != null) {
            if (opts.taskQueue() != null) {
                cmd.setTaskQueue(opts.taskQueue());
            }
            if (opts.retryPolicy() != null) {
                cmd.setRetryPolicy(opts.retryPolicy());
            }
            if (opts.workflowRunTimeout() != null) {
                cmd.setWorkflowRunTimeout(toProtoDuration(opts.workflowRunTimeout()));
            }
            if (opts.workflowTaskTimeout() != null) {
                cmd.setWorkflowTaskTimeout(toProtoDuration(opts.workflowTaskTimeout()));
            }
        }
        this.continuedAsNew = cmd.build();
        newCommands.add(Command.newBuilder().setContinueAsNewWorkflowExecution(this.continuedAsNew).build());
    }

    // ---- Query handlers ----

    private final Map<String, QueryHandler> queryHandlers = new HashMap<>();

    /**
     * Registers h to answer queries of queryType. Typically called once,
     * near the top of a workflow function/constructor, before its first
     * blocking primitive: registering it there guarantees a single
     * ExecuteRound has already run it by the time any query can arrive,
     * cache hit or miss alike. Mirrors execution.go's Execution.SetQueryHandler (Go).
     */
    public void setQueryHandler(String queryType, QueryHandler handler) {
        queryHandlers.put(queryType, handler);
    }

    /**
     * Answers a query using whatever handler is currently registered for
     * queryType. Safe to call between tasks/rounds: the coroutine that
     * registered the handler is parked in yield, not concurrently running,
     * so reading whatever state its closure captured cannot race with it.
     * Mirrors execution.go's Execution.InvokeQueryHandler (Go).
     */
    public Payload invokeQueryHandler(String queryType, Payload args) throws Exception {
        QueryHandler h = queryHandlers.get(queryType);
        if (h == null) {
            throw new IllegalStateException("no query handler registered for \"" + queryType + "\"");
        }
        return h.handle(args);
    }

    // ---- Signal handlers ----

    private record PendingSignal(String name, Payload payload) {
    }

    private final Map<String, SignalHandler> signalHandlers = new HashMap<>();
    private boolean signalPumpStarted = false;

    /**
     * Registers h to act on signals of signalName, delivered from a
     * {@code WorkflowExecutionSignaled} history event — see
     * {@code WorkflowContext.setSignalHandler}, the workflow-author-facing
     * wrapper. Like {@link #setQueryHandler}, typically called once, near
     * the top of a workflow function, before its first blocking call.
     *
     * <p>Unlike a query, a signal can already be sitting in history before
     * its handler is ever registered — a full, non-sticky replay scans a
     * run's entire history, including every signal it ever recorded,
     * before workflow code runs at all ({@link #loadHistory}) — so any
     * such backlog ({@link #pendingSignals}) is delivered right here,
     * synchronously, in the order recorded, the instant a matching handler
     * registers.
     *
     * <p>A signal recorded on a <i>later</i> task, once the handler is
     * already registered, is delivered by a dedicated internal coroutine
     * started (lazily, once per Execution) below, not directly from
     * {@link #loadNewEvents}: loadNewEvents runs outside any coroutine
     * (see {@code FlowdWorker}'s cache-hit path, which calls it before
     * {@code Dispatcher.executeRound}), so an uncaught throwable there
     * would crash the poll loop instead of failing just this one workflow
     * task. Routing every delivery through a real coroutine instead means
     * such a throwable (e.g. a malformed payload that fails to unmarshal
     * in {@code WorkflowContext.setSignalHandler}'s decode step) is caught
     * by the exact same {@code Dispatcher.firstPanic()} mechanism every
     * other workflow panic already goes through. Mirrors execution.go's
     * Execution.SetSignalHandler (Go).
     */
    public void setSignalHandler(String signalName, SignalHandler h) {
        signalHandlers.put(signalName, h);
        drainSignals();

        if (signalPumpStarted) {
            return;
        }
        signalPumpStarted = true;
        dispatcher.go(c -> {
            while (true) {
                drainSignals();
                c.yield();
            }
        });
    }

    /**
     * Delivers every currently-pending signal whose handler is now
     * registered, in the order recorded, leaving anything still unclaimed
     * (no handler registered for that signal_name yet — or ever, for a
     * signal_name this workflow simply never handles) queued for later.
     * Mirrors execution.go's Execution.drainSignals (Go).
     */
    private void drainSignals() {
        if (pendingSignals.isEmpty()) {
            return;
        }
        List<PendingSignal> remaining = new ArrayList<>();
        for (PendingSignal sig : pendingSignals) {
            SignalHandler h = signalHandlers.get(sig.name());
            if (h != null) {
                h.handle(sig.payload());
            } else {
                remaining.add(sig);
            }
        }
        pendingSignals.clear();
        pendingSignals.addAll(remaining);
    }

    // ---- Sticky-cache resume support ----

    /**
     * Clears the previous round's output before resuming a cached Execution
     * for a new task. A sticky Execution's Dispatcher and coroutines
     * persist in memory across tasks — that's the whole point — but
     * newCommands/result/err/continuedAsNew must not: without this, a later
     * task would resend commands already reported to the server in an
     * earlier response. Mirrors execution.go's Execution.ResetRoundOutput (Go).
     */
    public void resetRoundOutput() {
        newCommands.clear();
        result = null;
        err = null;
        continuedAsNew = null;
    }

    /**
     * Feeds events a cached Execution hasn't seen yet into it, to resume a
     * sticky task instead of a full loadHistory + fresh coroutines. A
     * resumed coroutine is a real, already-running virtual thread blocked
     * in yield — it physically cannot re-execute code it already ran past,
     * so only the two things a blocked coroutine is actually waiting to
     * observe are updated here: an activity/timer outcome and the new
     * task's start time. Mirrors execution.go's Execution.LoadNewEvents (Go).
     */
    public void loadNewEvents(List<HistoryEvent> events) {
        for (HistoryEvent ev : events) {
            switch (ev.getAttributesCase()) {
                case WORKFLOW_TASK_STARTED_EVENT_ATTRIBUTES ->
                        this.now = Instant.ofEpochSecond(ev.getEventTime().getSeconds(), ev.getEventTime().getNanos());
                case ACTIVITY_TASK_COMPLETED_EVENT_ATTRIBUTES -> {
                    ActivityTaskCompletedEventAttributes ac = ev.getActivityTaskCompletedEventAttributes();
                    activityOutcomes.put(ac.getActivityId(), ActivityOutcome.ofResult(ac.getResult()));
                }
                case ACTIVITY_TASK_FAILED_EVENT_ATTRIBUTES -> {
                    ActivityTaskFailedEventAttributes af = ev.getActivityTaskFailedEventAttributes();
                    activityOutcomes.put(af.getActivityId(), ActivityOutcome.ofFailure(af.getFailure()));
                }
                case TIMER_FIRED_EVENT_ATTRIBUTES -> firedTimers.add(ev.getTimerFiredEventAttributes().getTimerId());
                case WORKFLOW_EXECUTION_SIGNALED_EVENT_ATTRIBUTES -> {
                    WorkflowExecutionSignaledEventAttributes sig = ev.getWorkflowExecutionSignaledEventAttributes();
                    pendingSignals.add(new PendingSignal(sig.getSignalName(), sig.getInput()));
                }
                default -> {
                    // Nothing else is observable to an already-running coroutine.
                }
            }
        }
    }

    private static com.google.protobuf.Duration toProtoDuration(Duration d) {
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(d.getSeconds())
                .setNanos(d.getNano())
                .build();
    }
}
