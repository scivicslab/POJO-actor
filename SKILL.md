---
name: pojo-actor
description: "Complete reference for POJO-actor, a Java 21 library that runs any plain POJO as an actor using virtual threads, zero reflection and FIFO message ordering. Use when writing or reviewing code built on it — creating an ActorSystem, tell/ask/tellNow/askNow, child actors and naming, the managed thread pool, or diagnosing mailbox, lifecycle and exception-swallowing behaviour."
---

# POJO-actor SKILL

A lightweight library that runs any Java POJO as an actor.
Designed around Java 21 virtual threads, zero reflection, and FIFO message ordering guarantees.

---

## Maven dependency

```xml
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>pojo-actor</artifactId>
    <version>4.0.0</version>
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

| Method | Return type | Use case |
|--------|-------------|----------|
| `tell(action)` | `CompletableFuture<Void>` | Normal state mutation |
| `ask(action)` | `CompletableFuture<R>` | Normal data retrieval |
| `tell(action, pool)` | `CompletableFuture<Void>` | CPU-heavy processing |
| `ask(action, pool)` | `CompletableFuture<R>` | CPU-heavy processing + result |
| `tellNow(action)` | `CompletableFuture<Void>` | Emergency stop / priority processing |
| `askNow(action)` | `CompletableFuture<R>` | Monitoring / debugging |

### What each way of sending guarantees

`P` is the actor's object and `pool` is the `ManagedThreadPool` returned by `system.getManagedThreadPool()`.
"Running at once" counts every real thread and every virtual thread that is running a method of `P`.

| How it is sent | Messages start and end in the order sent | Real and virtual threads running a method of `P` at once | Real or virtual thread that runs the action | Test class that fixes it |
|---|---|---|---|---|
| `tell(action)`, `ask(action)` | kept | at most 1 | the actor's receive loop (a virtual thread) | `TellAskGuaranteesTest` |
| `tell(action, pool)`, `ask(action, pool)` | kept | at most 1 | a real thread of `pool`; the receive loop's virtual thread does not run `P` and waits until that real thread has finished | `PoolTellAskGuaranteesTest` |
| `tell(action, pool)`, `ask(action, pool)` sent from a real thread of `pool` (the inline case) | kept | at most 1 | a real thread of `pool`; when the sending real thread waits on the returned future with `get` or `join`, the receive loop gives the action back to it when the receive loop reaches the message, and it runs the action | `PoolThreadCallerGuaranteesTest` |
| `tellNow(action)`, `askNow(action)` | not kept: they skip the receive loop and start when called | not guaranteed: they run at the same time as the message the receive loop is running and as each other | a new virtual thread per call; neither the receive loop's virtual thread nor a real thread | `TellNowAskNowSpecTest` |

Why the receive loop waits for a pool job: it takes the next message only after the pool job has ended. The wait is what keeps the order
(design document `PD_230128_oo01`). The real thread of `pool` is what limits CPU-bound work to the size of the pool, and the receive loop's
virtual thread that waits holds no real thread. The full table, the reasons and the tests are in the design document
`ActorMessageGuarantees_261123_oo01`.

Notes outside the table:

- On the current main these guarantees are broken for the two pool rows: `PoolTellAskGuaranteesTest` and `PoolThreadCallerGuaranteesTest`
  fail in `aLaterMessageWaitsForTheEarlierOne`, `messagesStartInTheOrderTheyWereSent`, `atMostOneThreadIsInsideTheObject` and `noWriteIsLost`
  (8 tests), because a pool job is run directly on a real thread of `pool` and not through the receive loop.
- An `ask(action, pool)` sent from inside one of the same actor's own messages runs at once, as a method call would, so that the message does not wait for itself.
  It is not part of the order guarantee and no test fixes it.
- With `tellNow` and `askNow` the thread safety of `P` is the caller's responsibility.

### Waiting for an answer from a real thread of `pool`

A real thread of `pool` that waits with `get` or `join` for the future of `ask(action, pool)` or `tell(action, pool)` does not only block: while it waits it runs queued tasks of `pool` (`ManagedThreadPool.awaitHelping`), so that a pool whose real threads all wait for answers of actors still makes progress. A real thread that holds no actor's turn runs queued tasks in queue order, at most 32 nested in its stack. A real thread inside an actor's action runs only the action the receive loop hands back to it and the queued tasks of the actor it waits for; if it waits for a future that is not an actor's answer, for example `CompletableFuture.allOf(...)`, it runs no task. It never runs a task of an actor whose turn it holds, and an `ask` to such an actor runs at once, nested. `pool.awaitHelping(future, Duration)` checks its time limit before every task, and the interrupt status belongs to the waiting caller. Wait on the returned future itself, or call `pool.awaitHelping(future)`. Design document: `HelpingWaitOfManagedThreadPool_261123_oo01`; test class: `ManagedThreadPoolHelpingTest`.

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

## Distributed actors — a separate library

`DistributedActorSystem` is not part of POJO-actor. It lives in
[pojo-actor-distributed](https://github.com/scivicslab/pojo-actor-distributed), which depends on
POJO-actor and carries messages between actor systems in different processes.

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
