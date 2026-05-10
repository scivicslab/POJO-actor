# Accumulator — Result aggregation

A utility for collecting results from multiple actors or nodes.
The basic pattern is to wrap it with `ActorRef` for thread-safe access.

---

## Choosing an implementation

| Class | Output format |
|-------|--------------|
| `StreamingAccumulator` | Real-time output as results arrive |
| `BufferedAccumulator` | Output per source after all results are collected |
| `TableAccumulator` | Table output with source = row, type = column |
| `JsonAccumulator` | JSON format output |

---

## Basic usage

```java
ActorRef<Accumulator> results = system.actorOf("results", new TableAccumulator());

// Add results (thread-safe)
results.tell(a -> a.add("worker-1", "cpu",    "Intel Xeon"));
results.tell(a -> a.add("worker-1", "memory", "64GB"));
results.tell(a -> a.add("worker-2", "cpu",    "AMD EPYC"));

// Retrieve aggregated output
String summary = results.ask(Accumulator::getSummary).join();
int    count   = results.ask(Accumulator::getCount).join();

results.tell(Accumulator::clear);
```

---

## Combined with child actors

```java
ActorRef<Accumulator> acc = system.actorOf("results", new TableAccumulator());

parent.getNamesOfChildren().stream()
    .map(name -> system.getActor(name, Child.class))
    .forEach(child ->
        child.ask(c -> c.process())
             .thenAccept(result -> acc.tell(a -> a.add(child.getName(), "output", result)))
    );

// Use CompletableFuture.allOf to wait until all child results are collected
String summary = acc.ask(Accumulator::getSummary).join();
```

---

## TableAccumulator column width

```java
new TableAccumulator()     // default column width
new TableAccumulator(40)   // column width of 40 characters
```
