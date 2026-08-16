# flowd Java SDK

A Java SDK for [flowd](https://github.com/krishnakichuu/flowd) — a
Temporal/Cadence-style durable workflow engine backed by PostgreSQL.

Two APIs, both driving the same deterministic replay engine:

- **Annotation-based** (`@WorkflowInterface`/`@ActivityInterface`, typed
  client stubs) — the recommended API for new code, modeled conceptually
  on Temporal's Java SDK's developer experience.
- **Low-level** (`WorkflowHandler`/`ActivityHandler`, explicit
  `WorkflowContext` parameter) — a direct, function-reference-style port of
  `sdk/` (the Go SDK), predates the annotation layer, still fully
  supported and interoperable with it in the same worker.

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full design: module
structure, execution model, worker lifecycle, framework integration
strategy, and a step-by-step account of how this was built (including two
real bugs found via live-server testing and how they were fixed).

It is a fully standalone Maven project — a **sibling** of the Go engine
(`../flowd-engine`), not nested inside it and not part of its build graph.
It owns its own copy of the `.proto` contract and generates its own
gRPC/protobuf stubs at build time.

## Requirements

- **Java 21+** — the replay dispatcher uses virtual threads (Project Loom):
  a virtual thread per coroutine is the closest structural match to how the
  Go SDK uses one goroutine per coroutine.
- **Maven 3.9+**
- Nothing else — no local `protoc`/`protoc-gen-grpc-java` install required.
- To run something against it: a `flowd` server (`../flowd-engine`:
  `make compose-up && make migrate-up && make run-server`).

## Module layout

```
sdk-java/
├── flowd-sdk-core/                 framework-agnostic; every application depends on this
├── flowd-sdk-spring-boot-starter/  optional; depends on core + spring-boot-autoconfigure
└── examples/
    ├── plain-java/                 helloworkflow (low-level API), orderprocessing (annotation-based)
    └── spring-boot/                a full Spring Boot app using the starter
```

## Building

```bash
mvn install        # whole reactor (needed once, so examples resolve flowd-sdk-core)
mvn -pl flowd-sdk-core test
```

## Quickstart — annotation-based API, plain Java

```java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    String processOrder(String orderId);
}

public final class OrderWorkflowImpl implements OrderWorkflow {
    private final PaymentActivities payments = Workflow.newActivityStub(
            PaymentActivities.class, new ActivityOptions(null, null, Duration.ofSeconds(30)));

    @Override
    public String processOrder(String orderId) {
        return payments.chargeCard(orderId);
    }
}

@ActivityInterface
public interface PaymentActivities { String chargeCard(String orderId); }

public final class PaymentActivitiesImpl implements PaymentActivities {
    @Override
    public String chargeCard(String orderId) { return "receipt-" + orderId; }
}

// --- worker process ---
FlowdClient client = FlowdClient.dial("localhost:7233");
FlowdWorker worker = new FlowdWorker(client, "orders");
worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
worker.registerActivitiesImplementations(new PaymentActivitiesImpl());
worker.run(); // blocks

// --- caller process ---
WorkflowClient client = WorkflowClient.newInstance(FlowdClient.dial("localhost:7233"));
OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
    WorkflowOptions.newBuilder().setWorkflowId("order-4521").setTaskQueue("orders").build());
String receipt = wf.processOrder("order-4521"); // starts + blocks for the typed result
```

Run the real, checked-in version of this (`examples/plain-java`, package
`com.flowd.examples.orderprocessing`):

```bash
mvn -pl examples/plain-java exec:java -Dexec.mainClass=com.flowd.examples.orderprocessing.OrderWorkflowWorker
mvn -pl examples/plain-java exec:java -Dexec.mainClass=com.flowd.examples.orderprocessing.OrderWorkflowStarter -Dexec.args="order-4521"
```

The original low-level-API example still lives alongside it:

```bash
mvn -pl examples/plain-java exec:java -Dexec.mainClass=com.flowd.examples.helloworkflow.HelloWorkflowWorker
mvn -pl examples/plain-java exec:java -Dexec.mainClass=com.flowd.examples.helloworkflow.HelloWorkflowStarter -Dexec.args="World"
```

## Quickstart — Spring Boot

Add `flowd-sdk-spring-boot-starter` as a dependency — no `@EnableFlowd`
required. Activity implementations are ordinary `@Component`s (real DI);
workflow implementations are plain classes, discovered by classpath scan
(see ARCHITECTURE.md §7 for why).

The checked-in example (`examples/spring-boot-worker` +
`examples/spring-boot-client`) is two separate Spring Boot apps, matching
how a real deployment splits producer and consumer into separate
processes/pods — see ARCHITECTURE.md §9 for the full breakdown of how each
one's `application.yml` decides which role it plays.

```yaml
# consumer: application.yml sets flowd.task-queue, so a FlowdWorker
# auto-starts and long-polls it
flowd:
  address: localhost:7233
  task-queue: shipping-spring
  worker:
    base-packages: com.example.myapp
```

```yaml
# producer: flowd.task-queue is left unset, so FlowdWorkerAutoConfiguration
# never activates — only a WorkflowClient bean is available
flowd:
  address: localhost:7233
```

```bash
mvn -pl examples/spring-boot-worker spring-boot:run    # consumer, run first
mvn -pl examples/spring-boot-client spring-boot:run    # producer, starts one workflow and exits
```

## Testing

```bash
mvn -pl flowd-sdk-core test
```

Includes `DispatcherTest`/`ExecutionTest` (replay engine unit tests) and
`GoldenReplayTest` — replays a real Go-produced history fixture through
this independently-built Java engine and asserts an identical result, the
strongest correctness check available for a from-scratch port.

For a full end-to-end proof against a live server, run the worker and
starter mains above against `../flowd-engine`'s `make run-server`.
