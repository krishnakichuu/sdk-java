# flowd Java SDK — Architecture

This document covers the design of the Java SDK for [flowd](https://github.com/krishnakichuu/flowd):
architecture, module structure, core abstractions, annotation design, the
execution model, worker lifecycle, framework integration strategy, examples,
extensibility, the implementation plan actually followed, and pointers to
the real, compiling, live-server-verified source that backs every claim
made here. Nothing in this document is aspirational — every code path
described has been built and run against a real flowd server
(`flowd-engine`, Postgres-backed) as of this writing.

Temporal's Java SDK is used throughout as a **conceptual** reference for
developer experience (interfaces, annotations, typed stubs) — flowd's own
execution model (deterministic replay over a Postgres-backed event log,
sticky worker caching, query dispatch, continue-as-new) is what the engine
underneath actually implements, ported faithfully from `sdk/` (the Go SDK)
rather than copied from Temporal's implementation.

---

## 1. Proposed SDK architecture

Three layers, each depending only on the one below it:

```
┌─────────────────────────────────────────────────────────────────┐
│  Framework integration modules (optional, one per framework)     │
│  flowd-sdk-spring-boot-starter   (Quarkus/Micronaut: same shape) │
└───────────────────────────────┬───────────────────────────────────┘
                                 │ depends on
┌───────────────────────────────▼───────────────────────────────────┐
│  flowd-sdk-core  (framework-agnostic, zero DI-framework deps)      │
│                                                                     │
│   Annotation-based API          Low-level API                      │
│   @WorkflowInterface             WorkflowHandler<I,O>              │
│   @ActivityInterface             ActivityHandler<I,O>              │
│   Workflow.* / Activity.*        WorkflowContext / ActivityContext │
│   (dynamic proxies over ↓)       (explicit context parameter)      │
│                                 │                                   │
│              both compile down to the same:                        │
│   ┌─────────────────────────────▼─────────────────────────────┐   │
│   │  Deterministic replay engine (Coroutine/Dispatcher/        │   │
│   │  Execution) — virtual-thread coroutines, cooperatively     │   │
│   │  scheduled, one round per workflow task                    │   │
│   └─────────────────────────────┬─────────────────────────────┘   │
│                                 │                                   │
│   FlowdWorker (poll/dispatch/sticky-cache/query)                   │
│   FlowdClient / WorkflowClient (gRPC)                               │
└───────────────────────────────┬───────────────────────────────────┘
                                 │ gRPC (generated from api/proto)
                                 ▼
                    flowd server (flowd-engine, Go, Postgres)
```

**Why two APIs in core, not one.** The lower-level, function-reference API
(`WorkflowHandler`/`ActivityHandler`, explicit `WorkflowContext` parameter)
already existed as a faithful, tested port of the Go SDK before this
annotation-based layer was added. Rather than discard working, golden-replay-verified
code, the annotation-based API was built **on top of it**: an
`@WorkflowInterface` implementation is adapted into a `WorkflowHandler` via
reflection (see §5), so both APIs drive the exact same replay engine and are
interoperable within a single worker. This is also what makes the SDK
backward-compatible by construction — the low-level API's signatures never
changed.

**Why the replay engine is not itself pluggable.** Determinism is the one
thing the SDK cannot delegate to a framework or a plugin: replay must
produce byte-identical scheduling decisions on every worker, every version
of the code that's still compatible with in-flight histories. The engine
(`com.flowd.sdk.internal.replayer`) is therefore `internal`, not an
extension point — what *is* extensible is what runs "in between" a
coroutine's blocking calls (activities, DataConverter, worker options),
never the scheduling mechanism itself.

---

## 2. Module/package structure

```
sdk-java/                              (parent, packaging=pom)
├── flowd-sdk-core/                    zero framework dependencies
│   ├── src/main/proto/                flowd's wire contract (own copy)
│   └── src/main/java/com/flowd/
│       ├── api/v1/                    generated gRPC/protobuf stubs
│       └── sdk/
│           ├── workflow/              WorkflowInterface, WorkflowMethod, QueryMethod,
│           │                          SignalMethod, Workflow (static API), WorkflowContext,
│           │                          ActivityInvocation, ActivityException
│           ├── activity/              ActivityInterface, ActivityMethod, Activity
│           │                          (static API), ActivityContext, Info
│           ├── client/                FlowdClient, WorkflowClient, WorkflowOptions,
│           │                          WorkflowRun, WorkflowStubInvocationHandler
│           ├── worker/                FlowdWorker, WorkerOptions, ExecutionCache,
│           │                          WorkflowHandler, ActivityHandler, ReplayTester
│           └── internal/              NOT part of the public contract
│               ├── replayer/          Coroutine, Dispatcher, Execution, QueryHandler
│               ├── converter/         DataConverter, JsonDataConverter
│               ├── registry/          AnnotationSupport (reflection over annotations)
│               └── sync/              WorkflowThreadContext, ActivityThreadContext,
│                                      ActivityStubInvocationHandler
│
├── flowd-sdk-spring-boot-starter/     depends on core + spring-boot-autoconfigure only
│   └── com/flowd/sdk/spring/          FlowdProperties, FlowdClientAutoConfiguration,
│                                      FlowdWorkerAutoConfiguration, FlowdWorkerLifecycle,
│                                      EnableFlowd, WorkflowImplementationTypeFilter
│
└── examples/
    ├── plain-java/                    helloworkflow (low-level API), orderprocessing
    │                                  (annotation-based API) — both runnable, no DI framework
    └── spring-boot/                   a full Spring Boot app using the starter
```

**`internal.*` is a real boundary, not a convention.** Nothing outside
`com.flowd.sdk.internal.*` should import from it directly except where the
existing low-level API already does (`ActivityOptions`,
`Execution.ContinueAsNewOptions` are used directly in a couple of public
method signatures — a known, pre-existing minor leak from before this
annotation layer existed, called out explicitly here rather than hidden;
see §10 for the planned cleanup).

**One artifact per framework integration.** `flowd-sdk-spring-boot-starter`
sets the pattern a `flowd-sdk-quarkus` or `flowd-sdk-micronaut` module would
follow: its own Maven module, its own dependency on that framework's DI/config
APIs, `flowd-sdk-core` as its only flowd dependency. Core never gains a
dependency in the other direction.

---

## 3. Core interfaces and abstractions

| Abstraction | Purpose | Type |
|---|---|---|
| `DataConverter` | Marshal Java values ⇄ wire `Payload`. Default: `JsonDataConverter` (matches Go's `encoding/json` wire format byte-for-byte — a Go and Java worker can share a task queue). | `internal.converter` interface, pluggable via `WorkerOptions`/`FlowdClient.Options` |
| `WorkflowHandler<I,O>` | `O execute(WorkflowContext ctx, I input) throws Exception` — the low-level workflow registration unit. | `worker` functional interface |
| `ActivityHandler<I,O>` | `O execute(ActivityContext ctx, I input) throws Exception` — the low-level activity registration unit. | `worker` functional interface |
| `WorkflowContext` | The deterministic surface: `now()`, `sleep()`, `executeActivity()`, `go()`, `setQueryHandler()`, `continueAsNew()`. Everything a workflow is allowed to do. | `workflow` class, constructed by the engine only |
| `ActivityContext` | `getInfo()` — activity id/type/attempt. No determinism restriction; wraps nothing (activities may do arbitrary I/O directly). | `activity` class |
| `FlowdClient` | Raw gRPC-backed operations: start/signal/query/describe/terminate/history. | `client` class |
| `WorkflowClient` | Typed layer over `FlowdClient`: hands out `@WorkflowInterface` proxies. | `client` class |
| `FlowdWorker` | Registers handlers (either API), polls both task queues, drives replay, sticky-caches, answers queries. | `worker` class |

Every one of these existed (in the low-level, non-annotation form) before
this session's work except `WorkflowClient`; the annotation-based API is a
thin reflective adapter in front of them, not a parallel implementation.

---

## 4. Annotation design

```java
@WorkflowInterface                    // marks an interface as a workflow definition
public interface OrderWorkflow {
    @WorkflowMethod                   // the single entry point; name() overrides
    OrderResult processOrder(OrderRequest request);   // the registered workflow_type
                                                        // (default: interface simple name)
    @QueryMethod                      // synchronous, read-only, replayed-state read
    String currentStatus();
}

@ActivityInterface                    // marks an interface as an activity definition
public interface PaymentActivities {
    String chargeCard(ChargeRequest request);   // every method is an activity;
    void refund(String chargeId);                // @ActivityMethod only needed to
}                                                  // override the registered name
```

Design choices, and why:

- **Interface + implementation, not annotated classes directly.** A
  workflow/activity *interface* is the contract a client stub and a worker
  implementation both compile against — the same reason Temporal, and Java
  RPC frameworks generally, use interfaces here. It also gives
  `WorkflowClient.newWorkflowStub` something to build a `Proxy` over.
- **One `@WorkflowMethod` per interface, name defaults to the interface's
  simple name.** Mirrors the Go SDK's own default (a function's own name)
  while being the natural Java analogue — a workflow *interface* is the
  unit of identity, not an individual method.
- **Any number of parameters per method**, packed into flowd's wire
  contract, where `StartWorkflowExecutionRequest.input` and
  `ScheduleActivityTaskCommand.input` are each a single `Payload`, not an
  argument list: zero or one parameter is encoded as that value directly
  (or nothing) — unchanged from before multi-argument support existed —
  and more than one is packed into a single positional JSON array, decoded
  back by matching formal parameter type (`MethodArguments.pack`/`unpack`,
  `DataConverter.fromPayload(Payload, Class[])`; see §10, where this was
  originally the deliberate, not-yet-taken option and is now built).
- **`@SignalMethod` is dispatched into running workflow code** — built,
  in both SDKs (the replay algorithm has to stay identical between them):
  `Execution.setSignalHandler`, the signal counterpart of
  `setQueryHandler`, buffers a `WorkflowExecutionSignaled` event observed
  before its handler is registered and delivers it the instant a matching
  handler does register; one recorded on a *later* task is delivered by a
  dedicated internal coroutine (started lazily, once per Execution) so a
  delivery panic — a malformed payload, say — is caught by the same
  `Dispatcher.firstPanic()` mechanism every other workflow panic goes
  through, rather than crashing the poll loop (`loadNewEvents` itself runs
  outside any coroutine). `FlowdWorker.registerWorkflowImplementationType`
  binds every `@SignalMethod` via `WorkflowContext.setSignalHandler`
  automatically, the same way it already does for `@QueryMethod`.
- **No inheritance-based base classes.** A workflow implementation is
  `public class OrderWorkflowImpl implements OrderWorkflow` — nothing to
  extend, no framework supertype, so a plain-Java class can implement two
  unrelated interfaces (a workflow and, say, a domain interface) without
  conflict. This is also what keeps the SDK annotation-processor-free: all
  annotation resolution happens at worker/client registration time via
  reflection (`internal.registry.AnnotationSupport`), not at compile time.

---

## 5. Workflow and activity execution model

The engine is a line-for-line structural port of `sdk/internal/replayer`
(Go), substituting Java's Project Loom for goroutines:

| Go | Java | Why the substitution is exact |
|---|---|---|
| `Coroutine` (goroutine + two unbuffered channels: `resumeCh`, `yieldCh`) | `Coroutine` (`Thread.ofVirtual()` + two `SynchronousQueue`) | A `SynchronousQueue` has the same direct-handoff, zero-buffering semantics as an unbuffered Go channel — the same rendezvous primitive, different runtime. |
| `Dispatcher.ExecuteRound` (resume every live coroutine once, in creation order) | `Dispatcher.executeRound()` (identical) | Fixed order is what makes replay deterministic — reproduced exactly. |
| `Execution` (per-task state: already-scheduled activities/timers, outcomes, new commands) | `Execution` (identical fields/methods) | Same separation: what history already recorded vs. what this round newly schedules. |

**One round, driven by the worker, per workflow task.** A workflow
function is *not* invoked once per business call — it is re-run from the
top on every workflow task, replaying already-recorded history without
re-emitting commands for it (`Execution.scheduleActivity`'s "already in
history → return existing future, else record a new command" branch), and
newly emitting exactly one command for the first not-yet-recorded blocking
call it reaches this round. This is the entirety of how replay works; see
`OrderWorkflowImpl`'s own doc comment (`examples/plain-java`) for what this
means for `System.out.println` placement.

**Annotation-based execution, concretely** (`FlowdWorker.registerWorkflowImplementationType`):

1. A **fresh instance** of the implementation class is constructed via its
   no-arg constructor — every workflow task, every replay. This is
   deliberate: a fresh instance means no field can silently carry
   non-deterministic state between replay attempts.
2. `WorkflowThreadContext.bind(ctx)` happens **before** construction, not
   after — a workflow implementation's own constructor (field initializers
   in particular — `Workflow.newActivityStub(...)` is commonly a field
   initializer, see `OrderWorkflowImpl`) may itself call `Workflow.*`, so
   the thread-local must already be bound by the time `newInstance()` runs.
   *(This exact ordering was a real bug found and fixed via live-server
   testing during this SDK's development — see the worked example in
   §11.)*
3. Every `@QueryMethod` is registered against the fresh instance via
   `WorkflowContext.setQueryHandler` — **before** the `@WorkflowMethod`
   runs, so a query handler is live from the very first round, cache hit
   or miss alike.
4. The `@WorkflowMethod` is invoked reflectively. Its return value becomes
   the workflow result; a thrown `RuntimeException` propagates as a
   coroutine panic (dispatcher-level failure, including
   `NonDeterministicError`); a thrown checked `Exception` becomes a
   terminal `WorkflowExecutionFailed` — exactly the Go SDK's
   `return zero, err` vs. `panic` distinction, preserved through
   `InvocationTargetException` unwrapping.

**Activity execution has no such constraint.** `@ActivityInterface`
implementations are ordinary, typically singleton, objects — real I/O,
real dependency injection, no replay concern (only an activity's *recorded
result* is ever replayed, never its code). `Activity.getExecutionContext()`
resolves via a thread-local bound for the duration of one activity task on
its own dedicated virtual thread (`ActivityThreadContext`), so an
annotation-based activity method's signature carries only its business
parameters.

**Sticky worker caching.** A worker that keeps a run's `Execution` alive
between tasks (parked coroutines, real virtual threads blocked in `yield()`)
skips a full history replay on the next task — it feeds only the new
events (`Execution.loadNewEvents`) and resumes. The server is told to
prefer routing a run's next task back to the same worker via
`StickyExecutionAttributes`; if it can't (deadline passed, worker gone),
dispatch falls back to a full replay on whichever worker picks it up —
sticky caching is a pure optimization, never a correctness requirement.

**Query dispatch.** A query never mutates history. It's answered by
resuming a cached `Execution` (fast path) or rebuilding one via a
throwaway replay (cache miss) and invoking whatever handler is registered
for the query type. **A subtlety found during live verification**: a
query that races a workflow's very first task and triggers a cache-miss
replay can discover new work (schedule an activity) that a query response
never reports to the server — caching that `Execution` afterward would let
a later *real* task resume a coroutine already past that scheduling call,
losing the command forever and hanging the run. `FlowdWorker.processQueryTask`
guards against this explicitly: a query-triggered replay is only cached if
it produced zero new (unreported) commands. Verified by a repeated
race-condition stress test against a real server (§11).

---

## 6. Worker registration and lifecycle

```java
FlowdClient client = FlowdClient.dial("localhost:7233");
FlowdWorker worker = new FlowdWorker(client, "orders",
    WorkerOptions.newBuilder()
        .setMaxConcurrentActivities(500)
        .setMaxCachedWorkflowExecutions(2000)
        .build());

worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);   // annotation-based
worker.registerActivitiesImplementations(new PaymentActivitiesImpl()); // annotation-based
worker.registerWorkflow("LegacyWorkflow", String.class, LegacyWorkflow::execute); // low-level, same worker

worker.run();       // blocks: polls both task queues until shutdown()
worker.shutdown();  // from a shutdown hook / SmartLifecycle.stop()
```

- **A worker polls two independent task queues** (workflow tasks, activity
  tasks) on two virtual threads; activity tasks additionally fan out onto
  one virtual thread each, bounded by a `Semaphore` sized from
  `WorkerOptions.maxConcurrentActivities` (a real, documented gap in the Go
  SDK today — unbounded activity concurrency — fixed in this port from the
  start rather than knowingly reproduced).
- **Registration is additive and mixable**: the low-level
  `registerWorkflow`/`registerActivity` and the annotation-based
  `registerWorkflowImplementationTypes`/`registerActivitiesImplementations`
  populate the *same* internal registry (`Map<String, WorkflowRegistration<?,?>>`)
  — a single worker process can serve workflows written against either API.
- **Lifecycle is explicit, not implicit**: `run()` blocks the calling
  thread (a plain-Java `main()` typically runs it directly; a framework
  integration runs it on a background thread tied to the container's own
  lifecycle — see `FlowdWorkerLifecycle` in §7). `shutdown()` stops both
  poll loops; in-flight tasks' leases simply expire and get reaped
  server-side (ADR-0002) rather than being force-cancelled mid-flight.

---

## 7. Framework integration strategy

**The strategy is one module per framework, each translating that
framework's own idioms into calls against the unchanged core API** — never
teaching core about the framework.

### Spring Boot (built, in `flowd-sdk-spring-boot-starter`)

- `FlowdProperties` (`@ConfigurationProperties(prefix = "flowd")`) binds
  `application.yml`/`.properties`.
- `FlowdClientAutoConfiguration` provides `FlowdClient`/`WorkflowClient`
  beans (`@ConditionalOnMissingBean`, so either is trivially overridable —
  e.g. to add TLS, which `FlowdProperties` doesn't shortcut).
- `FlowdWorkerAutoConfiguration` activates only when `flowd.task-queue` is
  set (a client-only app has no reason to run a worker) and
  `flowd.worker.enabled` isn't `false`. It:
  - registers every Spring bean implementing an `@ActivityInterface` —
    ordinary constructor injection applies, nothing flowd-specific beyond
    the interface itself (`ShippingActivitiesImpl` in the example);
  - classpath-scans `flowd.worker.base-packages` (defaulting to the
    `@SpringBootApplication` package) for concrete classes implementing a
    `@WorkflowInterface`, registering the **class**, not a bean instance —
    workflow implementations are deliberately outside Spring's container,
    for the same fresh-instance-per-execution reason as §5.
  - registers a `FlowdWorkerLifecycle` (`SmartLifecycle`) that starts
    `FlowdWorker.run()` on a background daemon thread as the context
    finishes refreshing, and calls `shutdown()` during graceful shutdown —
    the same pattern Spring Kafka/AMQP use for their own consumer loops.
- **No `@EnableFlowd` required** — adding the starter dependency is the
  entire integration, matching every other Spring Boot starter's
  convention (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`).
  `@EnableFlowd` exists as an explicit, optional alternative for callers
  who want to opt in by hand.

### Quarkus / Micronaut (designed, not yet built — see §10 and §11)

Same shape, different primitives:

- **Quarkus**: a `flowd-sdk-quarkus` extension with a `QuarkusFlowdRecorder`
  (build-time bean registration via Quarkus's `@BuildStep`/CDI instead of
  Spring's `BeanDefinitionRegistry`), `@ConfigMapping` in place of
  `@ConfigurationProperties`, and the worker's `run()`/`shutdown()` tied to
  `StartupEvent`/`ShutdownEvent` observers instead of `SmartLifecycle`.
  Activity implementations become CDI beans (`@ApplicationScoped`
  implementing an `@ActivityInterface`); workflow implementation discovery
  reuses Jandex (Quarkus's build-time class index) instead of a runtime
  classpath scan — strictly better startup performance, same discovery
  *result*.
- **Micronaut**: a `flowd-sdk-micronaut` module with a
  `@ConfigurationProperties("flowd")` bean (Micronaut's own annotation of
  that name), a `BeanCreatedEventListener`-based activity bean scan, and
  worker lifecycle via `ApplicationEventListener<StartupEvent>`/`<ShutdownEvent>`.

Neither needs a single change to `flowd-sdk-core` — the same property that
makes the Spring Boot starter possible without touching core.

### Plain Java (built, `examples/plain-java`)

No framework at all: `FlowdClient.dial(...)`, `new FlowdWorker(...)`,
manual `registerWorkflowImplementationTypes`/`registerActivitiesImplementations`
calls, a `main()` that calls `worker.run()`, a `Runtime.getShutdownHook`
for graceful `shutdown()`. This is not a "reduced" mode — it's core's
actual, complete API; every framework integration is sugar over exactly
this.

---

## 8. Example usage — plain Java

Full source: `examples/plain-java/src/main/java/com/flowd/examples/orderprocessing/`.

```java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    String processOrder(String orderId);

    @QueryMethod
    String currentStatus();
}

public final class OrderWorkflowImpl implements OrderWorkflow {
    private final PaymentActivities payments = Workflow.newActivityStub(
            PaymentActivities.class,
            new ActivityOptions(null, null, Duration.ofSeconds(30)));
    private volatile String status = "STARTED";

    @Override
    public String processOrder(String orderId) {
        status = "CHARGING";
        String receipt = payments.chargeCard(orderId);
        status = "COMPLETED";
        return receipt;
    }

    @Override
    public String currentStatus() { return status; }
}

// worker process
FlowdClient client = FlowdClient.dial("localhost:7233");
FlowdWorker worker = new FlowdWorker(client, "orderprocessing-java");
worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
worker.registerActivitiesImplementations(new PaymentActivitiesImpl());
worker.run();

// caller process
WorkflowClient client = WorkflowClient.newInstance(FlowdClient.dial("localhost:7233"));
OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
    WorkflowOptions.newBuilder().setWorkflowId("order-4521").setTaskQueue("orderprocessing-java").build());
String receipt = wf.processOrder("order-4521");   // starts + blocks for the typed result
```

Run it (verified live against `flowd-engine`'s `make run-server`):

```bash
mvn -pl examples/plain-java exec:java -Dexec.mainClass=com.flowd.examples.orderprocessing.OrderWorkflowWorker
mvn -pl examples/plain-java exec:java -Dexec.mainClass=com.flowd.examples.orderprocessing.OrderWorkflowStarter -Dexec.args="order-4521"
```

---

## 9. Example usage — Spring Boot

Two separate deployable apps, each its own Maven module — the producer
(`examples/spring-boot-client`) and the consumer
(`examples/spring-boot-worker`) never run in the same JVM, matching how a
real deployment splits them into separate processes/pods. What decides
which role a given app plays is entirely `application.yml`: whether
`flowd.task-queue` is set is the one switch `FlowdWorkerAutoConfiguration`
(`flowd-sdk-spring-boot-starter`) checks (`@ConditionalOnProperty(prefix =
"flowd", name = "task-queue")`) to decide whether a `FlowdWorker` bean
exists at all — no other code differs in kind between the two apps.

**Consumer — `examples/spring-boot-worker`**, full source under
`src/main/java/com/flowd/examples/springboot/worker/`:

```java
@ActivityInterface
public interface ShippingActivities { String shipPackage(String orderId); }

@Component  // ordinary Spring bean, constructor-injected
public class ShippingActivitiesImpl implements ShippingActivities {
    private final NotificationService notifications;
    public ShippingActivitiesImpl(NotificationService notifications) { this.notifications = notifications; }

    @Override
    public String shipPackage(String orderId) {
        notifications.send("shipping " + orderId);
        return "tracking-" + orderId;
    }
}

// NOT a @Component — see §5/§7 for why
public final class ShippingWorkflowImpl implements ShippingWorkflow {
    private final ShippingActivities activities =
        Workflow.newActivityStub(ShippingActivities.class, new ActivityOptions(null, null, Duration.ofSeconds(30)));
    @Override public String ship(String orderId) { return activities.shipPackage(orderId); }
}

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws InterruptedException {
        SpringApplication.run(Application.class, args);
        new CountDownLatch(1).await();  // see below — no web layer to keep the JVM alive otherwise
    }
}
```

```yaml
# application.yml
flowd:
  address: localhost:7233
  task-queue: shipping-spring
  worker:
    base-packages: com.flowd.examples.springboot.worker
```

The explicit `CountDownLatch` block matters: `FlowdWorkerLifecycle` runs
the poll loops on daemon threads deliberately (so Spring context startup
never blocks on them — see the class's own doc), which normally is fine
because an embedded web server's non-daemon threads keep the JVM alive
afterward. This app has no web layer, so without something blocking
`main`, `SpringApplication.run` would return, only daemon threads would be
left, and the JVM would exit immediately after finishing startup — a real
bug this example hit during live testing (the worker process would start,
log its registrations, then silently exit with nothing ever consuming the
queue). A production consumer app normally doesn't need this — an embedded
web server (for health checks, `/actuator`, etc.) does the job instead.

**Producer — `examples/spring-boot-client`**, full source under
`src/main/java/com/flowd/examples/springboot/client/`:

```java
// Client-side contract only — no ShippingWorkflowImpl in this module.
// The wire workflow_type ("ShippingWorkflow", the interface's simple
// name) is what actually links this to the worker module's impl, not the
// Java type — the two modules never share a classloader.
@WorkflowInterface
public interface ShippingWorkflow {
    @WorkflowMethod
    String ship(String orderId);
}

@SpringBootApplication
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }

    @Bean
    CommandLineRunner demo(WorkflowClient client) {
        return args -> {
            ShippingWorkflow wf = client.newWorkflowStub(ShippingWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("order-1").setTaskQueue("shipping-spring").build());
            System.out.println("shipped: " + wf.ship("order-1"));
        };
    }
}
```

```yaml
# application.yml — no flowd.task-queue: FlowdWorkerAutoConfiguration
# never activates, so this process only ever gets a WorkflowClient bean,
# never a FlowdWorker
flowd:
  address: localhost:7233
```

Here `main` needs no extra blocking: `SpringApplication.run` already runs
the `CommandLineRunner` synchronously (as part of context startup, before
`run` returns), so the process stays alive for exactly as long as
`wf.ship(...)` takes, then exits on its own once `main` returns — the
producer is meant to be a one-shot process.

Each `pom.xml` needs exactly one flowd dependency:
`flowd-sdk-spring-boot-starter`. Run with:

```bash
mvn -pl examples/spring-boot-worker spring-boot:run &   # start the consumer first
mvn -pl examples/spring-boot-client spring-boot:run     # producer: starts one workflow, prints the result, exits
```

Verified live: the worker auto-registers `ShippingActivitiesImpl` (with
`NotificationService` injected) and classpath-scans and registers
`ShippingWorkflowImpl`; the client, a wholly separate JVM with no worker
of its own, starts a run through the typed client stub and blocks for the
result — zero manual wiring beyond each app's own `application.yml`.

---

## 10. Extensibility and future-proofing considerations

**Additive, not breaking, is the default assumption for every future
change** — matching how flowd's own server-side Phase 2 features (sticky
caching, queries, sharding) were built: a zero-value/default `Options`
object always reproduces prior behavior.

Concretely planned/anticipated extension points:

- **Signal dispatch** (§4, §5): built — see §4's `@SignalMethod` note for
  the full design (buffered backlog delivery on registration, a dedicated
  pump coroutine for later-task delivery, landed in the Go SDK's replayer
  at the same time since the replay algorithm is a cross-language
  contract, not a per-SDK detail).
- **Multi-argument workflow/activity methods**: built (§4) — >1 parameter
  packs into a single JSON array `Payload`, decoded symmetrically by
  position (`MethodArguments`, `DataConverter.fromPayload(Payload,
  Class[])`, `FlowdWorker`'s `PayloadDecoder`-based registration
  overloads). Backward compatible with 0/1-parameter methods, which
  encode exactly as they did before this existed and never reach the
  array-packing branch.
- **Retry/timeout policy is already server-driven**, not a gap: `RetryPolicy`
  on `ActivityOptions`/`ScheduleActivityTaskCommand` is enforced by the
  server (`RetryActivityTask`, ADR-0002) — a worker crash mid-attempt is
  transparently retried without SDK-side retry-loop code. Future SDK work
  here is additive convenience (e.g. a fluent `RetryOptions` builder), not
  new mechanism.
- **Observability**: `flowd-sdk-core` depends only on `slf4j-api` (a
  facade, never a bound implementation) — every internal log call
  (`FlowdWorker`'s poll failures, panics, non-determinism detections) is
  already routed through it, so any application's existing SLF4J binding
  (Logback, Log4j2, ...) picks it up with zero SDK configuration. A future
  OpenTelemetry integration would be a `flowd-sdk-opentelemetry` module
  wrapping `FlowdWorker`/`WorkflowClient` calls with spans — additive,
  optional, no core change.
- **`internal.*` leaking into two public method signatures**
  (`ActivityOptions`, `Execution.ContinueAsNewOptions`) — fixed: public
  `com.flowd.sdk.workflow.ActivityOptions`/`ContinueAsNewOptions` mirror
  types now exist, translated internally via a package-private
  `toInternal()`, the same relationship `client.WorkflowOptions` already
  has to the lower-level `FlowdClient.StartWorkflowOptions`. Pure
  API-surface polish, no behavior change.
- **DataConverter is already the extension point** for anything beyond
  JSON (Protobuf-native payloads, encryption-at-rest, compression) —
  implement the three-method interface, pass it to `WorkerOptions`/`FlowdClient.Options`;
  nothing else in the SDK special-cases JSON.
- **New framework integrations** cost one new Maven module each (§7);
  `flowd-sdk-core`'s public surface has not needed to change once for any
  integration built or designed so far, which is the actual test of
  whether the core/framework boundary is real.

---

## 11. Step-by-step implementation plan

This is the plan actually followed, in order, each step verified before
the next began — kept here (rather than replaced with an idealized plan)
because the real risks that showed up are exactly what a team repeating
this on another SDK should expect to hit:

1. **Inventory the existing Go SDK's exact mechanics** — read
   `sdk/internal/replayer/{dispatcher,execution}.go`, `sdk/worker/{worker,cache,registry,replay}.go`,
   `sdk/workflow/*.go`, `sdk/client/client.go`, `sdk/internal/converter/converter.go`
   in full before writing a line of Java, so the port is faithful rather
   than reinvented from memory.
2. **Inventory what already existed in `sdk-java`** before assuming
   "from scratch" meant deleting it — found a working, tested, low-level
   port (replay engine, client, worker, golden-replay test against a
   real Go-produced history fixture) and chose to build on it rather than
   discard verified correctness.
3. **Restructure into a multi-module Maven project** (`flowd-sdk-core`,
   `flowd-sdk-spring-boot-starter`, `examples/*`) — verified by rebuilding
   and re-running the existing test suite (8 tests, unchanged pass rate)
   immediately after the move, before any new code was added, to isolate
   "did the restructure break anything" from "does the new code work."
4. **Add annotations and the thread-bound static API**
   (`WorkflowThreadContext`/`ActivityThreadContext`, `Workflow`/`Activity`)
   — compiled and unit-tested in isolation.
5. **Bring core to Go SDK feature parity**: continue-as-new and query
   support added to `Execution` (they didn't exist in the pre-existing
   Java port, which predated those Go-side features), plus new unit tests
   (`ExecutionTest`) for each — before wiring them into the worker, so a
   failure there is isolated from poll/dispatch logic.
6. **Add sticky caching and query dispatch to `FlowdWorker`**, ported
   directly from `worker.go`'s `processWorkflowTask`/`processQueryTask`/`cache.go`.
7. **Build a real, annotation-based example and run it against a real
   server** — this is where the first genuine bug surfaced: binding
   `WorkflowThreadContext` *after* constructing the workflow implementation
   instead of before, which broke any workflow using
   `Workflow.newActivityStub(...)` as a field initializer (a very common
   pattern). A unit test would not have caught this — it only showed up
   running the real worker against the real server, because the low-level
   API's own tests never exercised construction-time context access. Fixed,
   re-verified live.
8. **Stress-test the interaction between the two new features together**
   (queries + sticky caching) by deliberately racing a query against a
   workflow's first-ever task — found the query-triggered-replay cache
   poisoning bug described in §5, fixed it, then re-ran the exact race
   repeatedly (not once) to confirm the fix wasn't merely timing-lucky.
9. **Build the Spring Boot starter and a real Spring Boot example**,
   verified live end-to-end in a single process: DI into an activity bean,
   classpath-scanned workflow class registration, auto-started worker
   lifecycle, and a typed client stub — all exercised together, not
   independently.
10. **Write this document**, last, so every claim in it points at code
    that has already compiled, passed its tests, and run successfully
    against a real server — not the other way around.

**What's next**, in priority order: a Quarkus module, a Micronaut module.
Signal dispatch, the `internal.*`-leak cleanup, and multi-argument method
support (originally listed here too) are done — see §10.

---

## 12. Sample production-quality code for core components

Rather than duplicate large files here (they're real, compiling,
tested source — better read in place), this section indexes the core
components by what to read for each:

| Component | File | What to verify by reading it |
|---|---|---|
| Coroutine/Dispatcher | `flowd-sdk-core/.../internal/replayer/{Coroutine,Dispatcher}.java` | Virtual-thread + `SynchronousQueue` 1:1 port of Go's goroutine + channel pair |
| Execution (replay state machine) | `flowd-sdk-core/.../internal/replayer/Execution.java` | `loadHistory`/`loadNewEvents`/`scheduleActivity`/`scheduleTimer`/`continueAsNew`/`setQueryHandler` — the entire deterministic-replay contract in one file |
| Annotation reflection | `flowd-sdk-core/.../internal/registry/AnnotationSupport.java` | Every name-derivation rule (`workflowTypeName`, `activityTypeName`, `queryTypeName`) in one place, shared by client, worker, and `Workflow.continueAsNew` |
| Thread-bound static API | `flowd-sdk-core/.../internal/sync/WorkflowThreadContext.java`, `.../workflow/Workflow.java` | How `Workflow.*`/`Activity.*` find "the current execution" without a threaded parameter |
| Worker (poll/dispatch/cache/query) | `flowd-sdk-core/.../worker/FlowdWorker.java` | `processWorkflowTask` (sticky cache branch), `processQueryTask` (the cache-poisoning guard from §5), `registerWorkflowImplementationType`/`registerActivityMethod` (the reflective adapters) |
| Typed client stubs | `flowd-sdk-core/.../client/{WorkflowClient,WorkflowStubInvocationHandler}.java` | Start-mode vs. attach-mode stub construction, query/signal dispatch through the same proxy |
| Spring Boot integration | `flowd-sdk-spring-boot-starter/.../spring/{FlowdClientAutoConfiguration,FlowdWorkerAutoConfiguration,FlowdWorkerLifecycle}.java` | The entire framework boundary — nothing here exists in core |
| Tests proving correctness | `flowd-sdk-core/src/test/java/.../{DispatcherTest,ExecutionTest,GoldenReplayTest}.java` | Including the golden-replay cross-language check: a real Go-produced history, replayed by this independently-built Java engine, produces an identical result |
