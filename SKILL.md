# POJO-actor SKILL

A lightweight library that runs any Java POJO as an actor.
Designed around Java 21 virtual threads, zero reflection, and FIFO message ordering guarantees.

---

## Maven dependency

```xml
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>pojo-actor</artifactId>
    <version>3.0.1</version>
</dependency>
```

Java 21 or later is required.

---

## Basic patterns

### Creating an ActorSystem and actors

```java
// Create one ActorSystem per application
ActorSystem system = new ActorSystem("my-system");          // thread count = CPU cores
ActorSystem system = new ActorSystem("my-system", 4);       // specify thread count
ActorSystem system = new ActorSystem.Builder("my-system").threadNum(8).build();

// Wrap any POJO as an actor — no inheritance or interface required
ActorRef<Counter>          ref = system.actorOf("counter", new Counter());
ActorRef<ArrayList<String>> ref = system.actorOf("list",    new ArrayList<>());
```

### tell / ask

```java
// tell — Fire and Forget
ref.tell(c -> c.increment());
ref.tell(c -> c.increment()).join();  // wait for completion

// ask — Request/Response
CompletableFuture<Integer> f = ref.ask(c -> c.getValue());
int value = f.join();
```

| Method | Queue | Thread-safe | Return type | Use case |
|--------|-------|-------------|-------------|----------|
| `tell(action)` | ✓ | automatic | `CompletableFuture<Void>` | Normal state mutation |
| `ask(action)` | ✓ | automatic | `CompletableFuture<R>` | Normal data retrieval |
| `tell(action, pool)` | ✗ | caller's responsibility | `CompletableFuture<Void>` | CPU-heavy processing |
| `ask(action, pool)` | ✗ | caller's responsibility | `CompletableFuture<R>` | CPU-heavy processing + result |
| `tellNow(action)` | ✗ bypass | caller's responsibility | `CompletableFuture<Void>` | Emergency stop / priority processing |
| `askNow(action)` | ✗ bypass | caller's responsibility | `CompletableFuture<R>` | Monitoring / debugging |

Queue-based methods are processed FIFO on the actor's internal thread, so no locking is needed on the caller side.

### Lifecycle

```java
// Check status
system.isAlive();              // entire system
system.isAlive("counter");     // specific actor
ref.isAlive();

// Stop
ref.close();                   // single actor
system.terminate();            // entire system (waits up to 60 s then forces shutdown)

// Scope management with try-with-resources
try (ActorRef<MyActor> actor = new ActorRef<>("temp", new MyActor())) {
    actor.tell(a -> a.doWork()).join();
}

// Clear the queue only (keep the actor alive)
int cleared = ref.clearPendingMessages();
```

---

## CPU-heavy processing

The actor's message loop (virtual thread) is designed for lightweight work.
Delegate CPU-bound processing to a managed thread pool.

```java
ExecutorService pool = system.getManagedThreadPool();
ref.tell(c -> c.heavyCompute(), pool);
double result = ref.ask(c -> c.calculate(), pool).join();

// Using multiple pools
system.addManagedThreadPool(8);   // index 1
system.addManagedThreadPool(2);   // index 2
ExecutorService pool1 = system.getManagedThreadPool(1);
```

---

## JsonState — dynamic state store inside an actor (since v2.10.0)

Each actor has its own independent JSON store. Read and write using XPath-style paths.

```java
// Write
ref.tell(a -> a.putJson("workflow.retry", 3));
ref.tell(a -> a.putJson("hosts[0]", "server1.example.com"));

// Read
int    retry = ref.ask(a -> a.getJsonInt("workflow.retry", 0)).join();
String host  = ref.ask(a -> a.getJsonString("hosts[0]")).join();
bool   found = ref.ask(a -> a.hasJson("workflow.retry")).join();

// Variable expansion inside workflows
// ${result}      → result of the previous action
// ${json.key}    → value from JsonState
String expanded = actor.expandVariables("Host is ${json.hostname}");

// Clear
ref.tell(a -> a.clearJsonState());
```

---

## Scheduler — periodic execution

Enqueues messages to an actor on a recurring schedule. Uses `ask()` internally, so scheduled messages are serialised FIFO with regular messages.
See `reference/scheduler.md` for details.

---

## Accumulator — result aggregation

A utility for collecting results from multiple actors. Wrap with `ActorRef` for thread-safe access.
See `reference/accumulator.md` for details.

---

## Child actors

A structure where a parent actor manages child actors. Commonly used to split work across children and have the parent aggregate the results.

```java
ActorRef<Parent> parent = system.actorOf("parent", new Parent());

// Create child actors (automatically registered in the ActorSystem)
ActorRef<Child> child1 = parent.createChild("child-1", new Child());
ActorRef<Child> child2 = parent.createChild("child-2", new Child());

// Inspect relationships
child1.getParentName();          // "parent"
parent.getNamesOfChildren();     // {"child-1", "child-2"}
```

### Typical pattern: parent dispatches work to children and aggregates results

```java
// Dispatch work to children in parallel
List<CompletableFuture<String>> futures = parent.getNamesOfChildren().stream()
    .map(name -> system.getActor(name, Child.class))
    .map(child -> child.ask(c -> c.process()))
    .toList();

// Wait for all children to complete, then aggregate
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
List<String> results = futures.stream().map(CompletableFuture::join).toList();
parent.tell(p -> p.aggregate(results));
```

### Combined with Accumulator

```java
ActorRef<Accumulator> acc = system.actorOf("results", new TableAccumulator());

parent.getNamesOfChildren().stream()
    .map(name -> system.getActor(name, Child.class))
    .forEach(child -> child.ask(c -> c.process())
        .thenAccept(result -> acc.tell(a -> a.add(child.getName(), "output", result))));

// Wait until all child results are collected
// (confirm all tells have completed before calling getSummary)
String summary = acc.ask(Accumulator::getSummary).join();
```

---

## Plugin registration (ActorProvider)

Auto-register actors from an external JAR via the service loader. Runtime loading (`DynamicActorLoader`) is also supported — see `reference/plugin.md` for details.

---

## Distributed actors (DistributedActorSystem)

An actor system that spans multiple nodes in an HPC cluster or on Kubernetes.
Supports two transports: HTTP (for Slurm, etc.) and Kafka (for Kubernetes).

See `reference/distributed.md` for details.

---

## Common mistakes

**Passing captured variables to tell/ask** — lambdas are not Serializable, so never mutate variables outside the actor directly.

```java
// Wrong: side-effect on an external variable
int[] count = {0};
ref.tell(a -> count[0] = a.getValue());  // not thread-safe

// Correct: retrieve the value with ask
int value = ref.ask(a -> a.getValue()).join();
```

**Sending CPU-heavy work through the queue** — blocking a virtual thread for a long time delays message processing for other actors. Delegate with `tell(action, pool)`.

**Forgetting to call terminate()** — `ActorSystem` holds a thread pool. If the JVM does not exit on its own, always call `system.terminate()`.
