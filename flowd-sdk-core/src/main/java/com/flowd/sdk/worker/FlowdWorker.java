package com.flowd.sdk.worker;

import com.flowd.api.v1.Failure;
import com.flowd.api.v1.HistoryEvent;
import com.flowd.api.v1.Payload;
import com.flowd.api.v1.PollActivityTaskQueueRequest;
import com.flowd.api.v1.PollActivityTaskQueueResponse;
import com.flowd.api.v1.PollWorkflowTaskQueueRequest;
import com.flowd.api.v1.PollWorkflowTaskQueueResponse;
import com.flowd.api.v1.RespondActivityTaskCompletedRequest;
import com.flowd.api.v1.RespondActivityTaskFailedRequest;
import com.flowd.api.v1.RespondQueryTaskCompletedRequest;
import com.flowd.api.v1.RespondWorkflowTaskCompletedRequest;
import com.flowd.api.v1.RespondWorkflowTaskFailedRequest;
import com.flowd.api.v1.StickyExecutionAttributes;
import com.flowd.api.v1.WorkflowServiceGrpc;
import com.flowd.sdk.activity.ActivityContext;
import com.flowd.sdk.activity.Info;
import com.flowd.sdk.client.FlowdClient;
import com.flowd.sdk.internal.converter.DataConverter;
import com.flowd.sdk.internal.converter.DataConverterException;
import com.flowd.sdk.internal.converter.MethodArguments;
import com.flowd.sdk.internal.registry.AnnotationSupport;
import com.flowd.sdk.internal.replayer.Execution;
import com.flowd.sdk.internal.replayer.NonDeterministicError;
import com.flowd.sdk.internal.sync.ActivityThreadContext;
import com.flowd.sdk.internal.sync.WorkflowThreadContext;
import com.flowd.sdk.workflow.WorkflowContext;
import com.google.protobuf.ByteString;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * FlowdWorker registers workflow and activity functions, long-polls both
 * task queues, and drives workflow tasks through the deterministic
 * dispatcher. Mirrors sdk/worker (Go)'s Worker + registry.go + worker.go +
 * cache.go directly, including sticky caching and query dispatch.
 *
 * <p>Two registration surfaces, fully interoperable against the same
 * poll/dispatch/replay machinery below — a single worker may mix both:
 * <ul>
 *   <li>{@link #registerWorkflow}/{@link #registerActivity} — the
 *   lower-level, explicit-name/function-reference API.
 *   <li>{@link #registerWorkflowImplementationTypes}/{@link
 *   #registerActivitiesImplementations} — the annotation-based API
 *   ({@code @WorkflowInterface}/{@code @ActivityInterface}), which
 *   resolves names and input types by reflection and adapts them into the
 *   same underlying registrations.
 * </ul>
 */
public final class FlowdWorker {
    private static final Logger log = LoggerFactory.getLogger(FlowdWorker.class);
    private static final long POLL_RETRY_BACKOFF_MS = 1000;

    private final WorkflowServiceGrpc.WorkflowServiceBlockingStub rpc;
    private final String namespace;
    private final String taskQueue;
    private final DataConverter converter;
    private final String identity;
    private final Semaphore activityConcurrency;
    private final List<Integer> taskQueuePartitions;

    private final ExecutionCache execCache;
    private final Duration stickyScheduleToStartTimeout;

    private final Map<String, WorkflowRegistration<?, ?>> workflows = new ConcurrentHashMap<>();
    private final Map<String, ActivityRegistration<?, ?>> activities = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private volatile Thread workflowPollThread;
    private volatile Thread activityPollThread;

    private record WorkflowRegistration<I, O>(PayloadDecoder<I> decoder, WorkflowHandler<I, O> handler) {
    }

    private record ActivityRegistration<I, O>(PayloadDecoder<I> decoder, ActivityHandler<I, O> handler) {
    }

    public FlowdWorker(FlowdClient client, String taskQueue) {
        this(client, taskQueue, WorkerOptions.defaultOptions());
    }

    /** @deprecated use {@link #FlowdWorker(FlowdClient, String, WorkerOptions)} */
    @Deprecated
    public FlowdWorker(FlowdClient client, String taskQueue, DataConverter converter, int maxConcurrentActivities) {
        this(client, taskQueue, WorkerOptions.newBuilder()
                .setDataConverter(converter)
                .setMaxConcurrentActivities(maxConcurrentActivities)
                .build());
    }

    public FlowdWorker(FlowdClient client, String taskQueue, WorkerOptions options) {
        this.rpc = client.workflowService();
        this.namespace = client.namespace();
        this.taskQueue = taskQueue;
        this.converter = options.converter;
        this.activityConcurrency = new Semaphore(options.maxConcurrentActivities);
        this.execCache = new ExecutionCache(options.maxCachedWorkflowExecutions);
        this.stickyScheduleToStartTimeout = options.stickyScheduleToStartTimeout;
        this.taskQueuePartitions = options.taskQueuePartitions;
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        this.identity = host + ":" + ProcessHandle.current().pid();
    }

    // ---- Low-level registration ----

    public <I, O> void registerWorkflow(String name, Class<I> inputType, WorkflowHandler<I, O> handler) {
        registerWorkflow(name, (conv, payload) -> conv.fromPayload(payload, inputType), handler);
    }

    /** Same as {@link #registerWorkflow(String, Class, WorkflowHandler)}, but with a caller-supplied decode step — used by the annotation-based API for multi-parameter {@code @WorkflowMethod}s (see {@link PayloadDecoder}). */
    public <I, O> void registerWorkflow(String name, PayloadDecoder<I> decoder, WorkflowHandler<I, O> handler) {
        workflows.put(name, new WorkflowRegistration<>(decoder, handler));
    }

    public <I, O> void registerActivity(String name, Class<I> inputType, ActivityHandler<I, O> handler) {
        registerActivity(name, (conv, payload) -> conv.fromPayload(payload, inputType), handler);
    }

    /** Same as {@link #registerActivity(String, Class, ActivityHandler)}, but with a caller-supplied decode step — used by the annotation-based API for multi-parameter {@code @ActivityMethod}s (see {@link PayloadDecoder}). */
    public <I, O> void registerActivity(String name, PayloadDecoder<I> decoder, ActivityHandler<I, O> handler) {
        activities.put(name, new ActivityRegistration<>(decoder, handler));
    }

    // ---- Annotation-based registration ----

    /**
     * Registers one or more {@code @WorkflowInterface} implementation
     * classes. Each implClass must implement exactly one
     * {@code @WorkflowInterface} and have a no-argument constructor
     * (accessible or not): a fresh instance is constructed for every
     * workflow execution (never reused across runs or replays), matching
     * how each workflow task already re-runs the workflow function from
     * scratch — a fresh instance means no field can silently carry
     * non-deterministic state between replay attempts by accident.
     * {@code @QueryMethod}/{@code @SignalMethod}-annotated methods on the
     * interface are wired up automatically via
     * {@link WorkflowContext#setQueryHandler}/{@link WorkflowContext#setSignalHandler}
     * before the {@code @WorkflowMethod} runs.
     */
    public void registerWorkflowImplementationTypes(Class<?>... implClasses) {
        for (Class<?> implClass : implClasses) {
            registerWorkflowImplementationType(implClass);
        }
    }

    private void registerWorkflowImplementationType(Class<?> implClass) {
        Class<?> wfInterface = AnnotationSupport.workflowInterfaceOf(implClass);
        Method wfMethod = AnnotationSupport.requireWorkflowMethod(wfInterface);
        String workflowType = AnnotationSupport.workflowTypeName(wfInterface);
        List<Method> queryMethods = AnnotationSupport.queryMethods(wfInterface);
        List<Method> signalMethods = AnnotationSupport.signalMethods(wfInterface);
        // getParameterTypes(), not just parameter 0: a @WorkflowMethod may
        // take more than one argument, packed into a single Payload as a
        // positional JSON array on the wire — see MethodArguments.
        Class<?>[] paramTypes = wfMethod.getParameterTypes();

        Constructor<?> ctor;
        try {
            ctor = implClass.getDeclaredConstructor();
            ctor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    implClass.getName() + " must have a no-argument constructor to be "
                            + "registered as a workflow implementation", e);
        }
        wfMethod.setAccessible(true);

        registerWorkflow(workflowType,
                (conv, payload) -> MethodArguments.unpack(conv, payload, paramTypes),
                (WorkflowContext ctx, Object[] callArgs) -> {
                    // Bound before construction, not after, and unbound in a single
                    // finally covering both: a workflow implementation's own
                    // constructor (field initializers in particular — see
                    // OrderWorkflowImpl's Workflow.newActivityStub(...) field) is
                    // allowed to call Workflow.* too, so the context must already
                    // be bound by the time newInstance() runs, and must be cleared
                    // even if construction itself is what fails.
                    WorkflowThreadContext.bind(ctx);
                    try {
                        Object impl;
                        try {
                            impl = ctor.newInstance();
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("failed to instantiate " + implClass.getName(), e);
                        }
                        for (Method qm : queryMethods) {
                            bindQueryHandler(ctx, impl, qm);
                        }
                        for (Method sm : signalMethods) {
                            bindSignalHandler(ctx, impl, sm);
                        }
                        return wfMethod.invoke(impl, callArgs);
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof Exception ex) {
                            throw ex; // RuntimeException (a real panic) or checked (a business failure) — both rethrown as-is
                        }
                        throw new RuntimeException(cause);
                    } finally {
                        WorkflowThreadContext.unbind();
                    }
                });
    }

    private static void bindQueryHandler(WorkflowContext ctx, Object impl, Method qm) {
        String queryType = AnnotationSupport.queryTypeName(qm);
        Class<?> argType = qm.getParameterCount() > 0 ? qm.getParameterTypes()[0] : null;
        qm.setAccessible(true);
        ctx.setQueryHandler(queryType, argType, arg -> {
            try {
                Object[] callArgs = qm.getParameterCount() > 0 ? new Object[]{arg} : new Object[0];
                return qm.invoke(impl, callArgs);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot invoke query method " + qm, e);
            }
        });
    }

    private static void bindSignalHandler(WorkflowContext ctx, Object impl, Method sm) {
        String signalName = AnnotationSupport.signalName(sm);
        Class<?> argType = sm.getParameterCount() > 0 ? sm.getParameterTypes()[0] : null;
        sm.setAccessible(true);
        ctx.setSignalHandler(signalName, argType, arg -> {
            try {
                Object[] callArgs = sm.getParameterCount() > 0 ? new Object[]{arg} : new Object[0];
                sm.invoke(impl, callArgs);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot invoke signal method " + sm, e);
            }
        });
    }

    /**
     * Registers one or more already-constructed {@code @ActivityInterface}
     * implementation instances — unlike workflow implementations,
     * activities have no determinism constraint, so the same instance
     * (typically holding real dependencies: an HTTP client, a database
     * pool, ...) is reused across every invocation, exactly like an
     * ordinary singleton service object. An instance may implement more
     * than one {@code @ActivityInterface}.
     */
    public void registerActivitiesImplementations(Object... activityImpls) {
        for (Object impl : activityImpls) {
            for (Class<?> iface : AnnotationSupport.activityInterfacesOf(impl.getClass())) {
                for (Method m : iface.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) {
                        continue;
                    }
                    registerActivityMethod(impl, m);
                }
            }
        }
    }

    private void registerActivityMethod(Object impl, Method m) {
        String activityType = AnnotationSupport.activityTypeName(m);
        // getParameterTypes(), not just parameter 0: an @ActivityMethod may
        // take more than one argument, packed into a single Payload as a
        // positional JSON array on the wire — see MethodArguments.
        Class<?>[] paramTypes = m.getParameterTypes();
        m.setAccessible(true);
        registerActivity(activityType,
                (conv, payload) -> MethodArguments.unpack(conv, payload, paramTypes),
                (ActivityContext actCtx, Object[] callArgs) -> {
                    ActivityThreadContext.bind(actCtx);
                    try {
                        return m.invoke(impl, callArgs);
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof Exception ex) {
                            throw ex;
                        }
                        throw new RuntimeException(cause);
                    } finally {
                        ActivityThreadContext.unbind();
                    }
                });
    }

    // ---- Lifecycle ----

    /** Blocks polling both task queues until {@link #shutdown()} is called. */
    public void run() {
        workflowPollThread = Thread.ofVirtual().unstarted(this::pollWorkflowTasks);
        activityPollThread = Thread.ofVirtual().unstarted(this::pollActivityTasks);
        workflowPollThread.start();
        activityPollThread.start();
        try {
            workflowPollThread.join();
            activityPollThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        running = false;
        if (workflowPollThread != null) {
            workflowPollThread.interrupt();
        }
        if (activityPollThread != null) {
            activityPollThread.interrupt();
        }
    }

    private void pollWorkflowTasks() {
        while (running) {
            PollWorkflowTaskQueueResponse resp;
            try {
                resp = rpc.pollWorkflowTaskQueue(PollWorkflowTaskQueueRequest.newBuilder()
                        .setNamespace(namespace)
                        .setTaskQueue(taskQueue)
                        .setIdentity(identity)
                        .addAllTaskQueuePartitions(taskQueuePartitions)
                        .build());
            } catch (StatusRuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("poll workflow task queue \"{}\" failed, retrying", taskQueue, e);
                sleepQuietly(POLL_RETRY_BACKOFF_MS);
                continue;
            }
            if (resp.getTaskToken().isEmpty()) {
                continue; // long-poll timed out with nothing available — poll again immediately
            }
            processWorkflowTask(resp);
        }
    }

    private void pollActivityTasks() {
        while (running) {
            // Acquire a concurrency slot before polling, not after dispatch,
            // so this worker never holds a dispatched task's time-bounded
            // lease while merely waiting for processing capacity to free up.
            try {
                activityConcurrency.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            PollActivityTaskQueueResponse resp;
            try {
                resp = rpc.pollActivityTaskQueue(PollActivityTaskQueueRequest.newBuilder()
                        .setNamespace(namespace)
                        .setTaskQueue(taskQueue)
                        .setIdentity(identity)
                        .addAllTaskQueuePartitions(taskQueuePartitions)
                        .build());
            } catch (StatusRuntimeException e) {
                activityConcurrency.release();
                if (!running) {
                    return;
                }
                log.warn("poll activity task queue \"{}\" failed, retrying", taskQueue, e);
                sleepQuietly(POLL_RETRY_BACKOFF_MS);
                continue;
            }
            if (resp.getTaskToken().isEmpty()) {
                activityConcurrency.release();
                continue;
            }
            Thread.ofVirtual().start(() -> {
                try {
                    processActivityTask(resp);
                } finally {
                    activityConcurrency.release();
                }
            });
        }
    }

    // ---- Workflow tasks: sticky cache + query dispatch ----

    /**
     * Advances a run one step. On a cache hit — this worker already has
     * the run's Execution cached from a previous task, and the server
     * routed this one back sticky — resumes that Execution with only the
     * events it hasn't seen yet, skipping a full replay entirely (see
     * {@link Execution#loadNewEvents}). On a miss — the first task, a
     * cache eviction, or a different worker picked this run up — replays
     * the full history through a fresh Execution/Dispatcher. Either way,
     * reports the resulting commands — or a distinct non-determinism
     * failure — back to the server. Mirrors worker.go's
     * processWorkflowTask (Go) exactly, including query-task priority.
     */
    @SuppressWarnings("unchecked")
    private void processWorkflowTask(PollWorkflowTaskQueueResponse resp) {
        if (resp.hasQueryTask()) {
            processQueryTask(resp);
            return;
        }

        ExecutionCache.Key key = new ExecutionCache.Key(
                resp.getWorkflowExecution().getWorkflowId(), resp.getWorkflowExecution().getRunId());

        Execution exec;
        String workflowType;

        ExecutionCache.CachedExecution cached = execCache.get(key);
        if (cached != null) {
            exec = cached.exec();
            workflowType = cached.workflowType();
            exec.resetRoundOutput();
            exec.loadNewEvents(eventsAfter(resp.getHistoryList(), cached.lastEventId()));
            exec.dispatcher.executeRound();
        } else {
            exec = new Execution();
            Execution.LoadedHistory loaded = exec.loadHistory(resp.getHistoryList());
            workflowType = loaded.workflowType();

            WorkflowRegistration<Object, Object> reg = (WorkflowRegistration<Object, Object>) workflows.get(workflowType);
            if (reg == null) {
                respondWorkflowTaskFailed(resp.getTaskToken(), "unregistered workflow type \"" + workflowType + "\"");
                return;
            }
            Object input;
            try {
                input = reg.decoder().decode(converter, loaded.input());
            } catch (DataConverterException e) {
                respondWorkflowTaskFailed(resp.getTaskToken(), "input unmarshal: " + e.getMessage());
                return;
            }
            WorkflowRunner.run(exec, reg.handler(), input, converter);
            exec.dispatcher.executeRound();
        }

        Throwable panic = exec.dispatcher.firstPanic();
        if (panic != null) {
            execCache.delete(key); // cached state, if any, is exactly what's suspect
            if (panic instanceof NonDeterministicError) {
                log.error("non-deterministic workflow detected: workflow_type={}", workflowType, panic);
            } else {
                log.error("workflow panic: workflow_type={}", workflowType, panic);
            }
            respondWorkflowTaskFailed(resp.getTaskToken(), panic.getMessage() != null ? panic.getMessage() : panic.toString());
            return;
        }

        StickyExecutionAttributes stickyAttrs = null;
        if (exec.result == null && exec.err == null && exec.continuedAsNew == null) {
            List<HistoryEvent> history = resp.getHistoryList();
            long lastEventId = history.get(history.size() - 1).getEventId();
            execCache.put(key, new ExecutionCache.CachedExecution(exec, workflowType, lastEventId));
            stickyAttrs = StickyExecutionAttributes.newBuilder()
                    .setWorkerIdentity(identity)
                    .setScheduleToStartTimeout(toProtoDuration(stickyScheduleToStartTimeout))
                    .build();
        } else {
            // Terminal, or continued into a different run_id: nothing more
            // will ever be dispatched for this run, so there's nothing to
            // keep cached.
            execCache.delete(key);
        }

        RespondWorkflowTaskCompletedRequest.Builder req = RespondWorkflowTaskCompletedRequest.newBuilder()
                .setTaskToken(resp.getTaskToken())
                .addAllCommands(exec.newCommands)
                .setIdentity(identity);
        if (stickyAttrs != null) {
            req.setStickyExecutionAttributes(stickyAttrs);
        }
        try {
            rpc.respondWorkflowTaskCompleted(req.build());
        } catch (StatusRuntimeException e) {
            log.warn("respond workflow task completed failed (the task's lease will expire and be reaped)", e);
        }
    }

    /** The suffix of history with event_id > lastEventId — what a cache hit still needs fed into its already-resumed Execution. */
    private static List<HistoryEvent> eventsAfter(List<HistoryEvent> history, long lastEventId) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).getEventId() > lastEventId) {
                return history.subList(i, history.size());
            }
        }
        return List.of();
    }

    private void respondWorkflowTaskFailed(ByteString token, String message) {
        try {
            rpc.respondWorkflowTaskFailed(RespondWorkflowTaskFailedRequest.newBuilder()
                    .setTaskToken(token)
                    .setFailure(Failure.newBuilder().setMessage(message))
                    .setIdentity(identity)
                    .build());
        } catch (StatusRuntimeException e) {
            log.warn("respond workflow task failed (RPC itself failed)", e);
        }
    }

    /**
     * Answers a query dispatch: resumes this worker's cached Execution if
     * it has one, or rebuilds it via a full replay if not, then invokes
     * whatever handler the workflow registered for this query_type —
     * entirely in memory, no commands, no RespondWorkflowTaskCompleted,
     * nothing appended to history. A successful replay-to-rebuild is
     * opportunistically cached too, same as a real workflow task cache
     * miss. Mirrors worker.go's processQueryTask (Go).
     */
    @SuppressWarnings("unchecked")
    private void processQueryTask(PollWorkflowTaskQueueResponse resp) {
        ExecutionCache.Key key = new ExecutionCache.Key(
                resp.getWorkflowExecution().getWorkflowId(), resp.getWorkflowExecution().getRunId());

        Execution exec;
        ExecutionCache.CachedExecution cached = execCache.get(key);
        if (cached != null) {
            exec = cached.exec();
        } else {
            exec = new Execution();
            Execution.LoadedHistory loaded = exec.loadHistory(resp.getHistoryList());

            WorkflowRegistration<Object, Object> reg = (WorkflowRegistration<Object, Object>) workflows.get(loaded.workflowType());
            if (reg == null) {
                respondQueryTaskFailed(resp.getTaskToken(), "unregistered workflow type \"" + loaded.workflowType() + "\"");
                return;
            }
            Object input;
            try {
                input = reg.decoder().decode(converter, loaded.input());
            } catch (DataConverterException e) {
                respondQueryTaskFailed(resp.getTaskToken(), "input unmarshal: " + e.getMessage());
                return;
            }
            WorkflowRunner.run(exec, reg.handler(), input, converter);
            exec.dispatcher.executeRound();

            Throwable panic = exec.dispatcher.firstPanic();
            if (panic != null) {
                respondQueryTaskFailed(resp.getTaskToken(), panic.getMessage() != null ? panic.getMessage() : panic.toString());
                return;
            }
            // Only cache if this replay didn't discover NEW work (a
            // ScheduleActivityTask/StartTimer/... not already reflected in
            // history): a query never calls RespondWorkflowTaskCompleted,
            // so any such command is silently lost the instant this round
            // ends. Caching the Execution anyway would let the coroutine
            // resume later already past that scheduling call — it would
            // never call it again, so a subsequent REAL workflow task
            // would report zero commands and the run would hang forever,
            // stuck on work the server was never told to do. Safe to cache
            // only when the replay purely reconstructed state and blocked
            // on something history already recorded (the common case: a
            // query against a workflow that already has at least one real
            // workflow task behind it).
            if (exec.result == null && exec.err == null && exec.continuedAsNew == null && exec.newCommands.isEmpty()) {
                List<HistoryEvent> history = resp.getHistoryList();
                execCache.put(key, new ExecutionCache.CachedExecution(exec, loaded.workflowType(), history.get(history.size() - 1).getEventId()));
            }
        }

        Payload result;
        try {
            result = exec.invokeQueryHandler(resp.getQueryTask().getQueryType(), resp.getQueryTask().getQueryArgs());
        } catch (Exception e) {
            respondQueryTaskFailed(resp.getTaskToken(), e.getMessage() != null ? e.getMessage() : e.toString());
            return;
        }
        try {
            rpc.respondQueryTaskCompleted(RespondQueryTaskCompletedRequest.newBuilder()
                    .setTaskToken(resp.getTaskToken())
                    .setResult(result != null ? result : Payload.getDefaultInstance())
                    .build());
        } catch (StatusRuntimeException e) {
            log.warn("respond query task completed failed", e);
        }
    }

    private void respondQueryTaskFailed(ByteString token, String message) {
        try {
            rpc.respondQueryTaskCompleted(RespondQueryTaskCompletedRequest.newBuilder()
                    .setTaskToken(token)
                    .setFailure(Failure.newBuilder().setMessage(message))
                    .build());
        } catch (StatusRuntimeException e) {
            log.warn("respond query task failed (RPC itself failed)", e);
        }
    }

    // ---- Activity tasks ----

    @SuppressWarnings("unchecked")
    private void processActivityTask(PollActivityTaskQueueResponse resp) {
        ActivityRegistration<Object, Object> reg = (ActivityRegistration<Object, Object>) activities.get(resp.getActivityType());
        if (reg == null) {
            respondActivityTaskFailed(resp.getTaskToken(), "UnregisteredActivityType",
                    "unregistered activity type \"" + resp.getActivityType() + "\"");
            return;
        }

        Object input;
        try {
            input = reg.decoder().decode(converter, resp.getInput());
        } catch (DataConverterException e) {
            respondActivityTaskFailed(resp.getTaskToken(), "InputUnmarshalError", e.getMessage());
            return;
        }

        ActivityContext actCtx = new ActivityContext(
                new Info(resp.getActivityId(), resp.getActivityType(), resp.getCurrentAttempt()));

        Object out;
        try {
            out = reg.handler().execute(actCtx, input);
        } catch (Throwable t) {
            // Unlike workflows, both a returned error and a panic are
            // treated identically here — activities have no
            // determinism/replay concern, matching Go's processActivityTask.
            respondActivityTaskFailed(resp.getTaskToken(), t.getClass().getName(),
                    t.getMessage() != null ? t.getMessage() : t.toString());
            return;
        }

        Payload payload;
        try {
            payload = converter.toPayload(out);
        } catch (DataConverterException e) {
            respondActivityTaskFailed(resp.getTaskToken(), "MarshalError", e.getMessage());
            return;
        }

        try {
            rpc.respondActivityTaskCompleted(RespondActivityTaskCompletedRequest.newBuilder()
                    .setTaskToken(resp.getTaskToken())
                    .setResult(payload)
                    .setIdentity(identity)
                    .build());
        } catch (StatusRuntimeException e) {
            log.warn("respond activity task completed failed", e);
        }
    }

    private void respondActivityTaskFailed(ByteString token, String errType, String message) {
        try {
            rpc.respondActivityTaskFailed(RespondActivityTaskFailedRequest.newBuilder()
                    .setTaskToken(token)
                    .setFailure(Failure.newBuilder().setType(errType).setMessage(message != null ? message : ""))
                    .setIdentity(identity)
                    .build());
        } catch (StatusRuntimeException e) {
            log.warn("respond activity task failed (RPC itself failed)", e);
        }
    }

    private static com.google.protobuf.Duration toProtoDuration(Duration d) {
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(d.getSeconds())
                .setNanos(d.getNano())
                .build();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
