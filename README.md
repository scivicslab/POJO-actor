# POJO-actor

A lightweight, GraalVM Native Image compatible actor model library for Java that turns ordinary POJOs (Plain Old Java Objects) into actors with minimal overhead. 

[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Javadoc](https://img.shields.io/badge/javadoc-2.12.0-brightgreen.svg)](https://scivicslab.github.io/POJO-actor/)


The actor model is a programming paradigm where independent entities (actors) communicate through message passing, eliminating the need for locks and avoiding the complexities of shared-state concurrency. Traditionally, using the actor model required specialized frameworks, and because these frameworks relied on real operating system threads, you could only create as many actors as you had CPU cores — typically just a handful. However, recent advancements in the JDK, particularly the introduction of virtual threads in Java 21, have changed everything: now even an ordinary laptop can handle tens of thousands of actors simultaneously.


## Architecture

POJO-actor implements a simplified actor model built on modern Java features. Built with just ~800 lines of code, POJO-actor delivers a practical actor model implementation without sacrificing functionality or performance.

- ActorSystem: Manages actor lifecycle and configurable managed thread pools
- ActorRef: Reference to an actor that provides tell() and ask() messaging interface
- Virtual Threads: Each actor runs on its own virtual thread for lightweight message handling
- Managed Thread Pools: Heavy computations are delegated to configurable thread pools
- Zero Reflection: Built entirely with standard JDK APIs, making it GraalVM Native Image ready




## Quick Start

Requirements

- Java 21 or higher
- Maven 3.6+


Maven Dependency

```
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>pojo-actor</artifactId>
    <version>2.12.0</version>
</dependency>
```

### Installing Development Version (v2.12.1)

To use the latest development version:

```bash
git clone https://github.com/scivicslab/POJO-actor
cd POJO-actor
git checkout v2.12.1
mvn install
```

**Note:** Do not use `mvn clean install`. Use `mvn install` only. The `clean` target may cause issues due to the Maven repository configuration in pom.xml.

### Building from Source

```bash
# Compile and test
mvn test

# Build JAR
mvn package

# Generate Javadoc and copy to docs/ directory
mvn verify

# Or generate Javadoc only (without copying to docs/)
mvn javadoc:javadoc
```



## Basic Usage of the Core Components

### Any POJO Can Become an Actor

One of POJO-actor's biggest advantages is that you don't need to design your code specifically for the actor model from the beginning. **Any existing Java object can instantly become an actor**, including standard library classes:

```java
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

// Turn a standard ArrayList into an actor - no modifications needed!
ActorSystem system = new ActorSystem("listSystem");
ActorRef<ArrayList<String>> listActor = system.actorOf("myList", new ArrayList<String>());

// Send messages to the ArrayList actor
listActor.tell(list -> list.add("Hello"));
listActor.tell(list -> list.add("World"));
listActor.tell(list -> list.add("from"));
listActor.tell(list -> list.add("POJO-actor"));

// Query the list size
CompletableFuture<Integer> sizeResult = listActor.ask(list -> list.size());
System.out.println("List size: " + sizeResult.get()); // Prints: List size: 4

// Get specific elements
CompletableFuture<String> firstElement = listActor.ask(list -> list.get(0));
System.out.println("First element: " + firstElement.get()); // Prints: First element: Hello

// Even complex operations work
CompletableFuture<String> joinedResult = listActor.ask(list -> 
    String.join(" ", list));
System.out.println(joinedResult.get()); // Prints: Hello World from POJO-actor

system.terminate();
```

This means you can:
- **Retrofit existing codebases** without architectural changes
- **Protect any object** with actor-based thread safety
- **Scale incrementally** by converting objects to actors as needed
- **Reuse existing POJOs** without any modifications

### Massive Actor Scalability

Thanks to virtual threads, POJO-actor can handle thousands of actors efficiently. Here's an example creating 10,000 counter actors:

```java
ActorSystem system = new ActorSystem("massiveSystem", 4); // Only 4 CPU threads for computation
List<ActorRef<Counter>> actors = new ArrayList<>();

// Create 10,000 actors - no problem with virtual threads!
for (int i = 0; i < 10000; i++) {
    ActorRef<Counter> actor = system.actorOf("counter" + i, new Counter());
    actors.add(actor);
}

// Send messages to all actors concurrently
List<CompletableFuture<Void>> futures = new ArrayList<>();
for (ActorRef<Counter> actor : actors) {
    futures.add(actor.tell(c -> c.increment()));
}

// Wait for all messages to be processed
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

// Verify results from all 10,000 actors
for (ActorRef<Counter> actor : actors) {
    int value = actor.ask(c -> c.getValue()).get();
    assert value == 1; // Each counter was incremented once
}

System.out.println("Successfully processed messages for 10,000 actors!");
system.terminate();
```

This demonstrates POJO-actor's ability to:
- Create thousands of actors without thread exhaustion
- Control CPU usage (only 4 threads for heavy computation)
- Scale beyond traditional thread-per-actor limitations


### Performance

Virtual threads excel at lightweight operations like message passing and state updates, but should not perform heavy CPU computations directly. For CPU-intensive tasks, delegate to a managed thread pool:

```java
// Light operation → virtual thread (default)
actor.ask(a -> a.updateCounter());

// Heavy computation → managed thread pool
actor.ask(a -> a.performMatrixMultiplication(), system.getManagedThreadPool());
```


## Workflow Engine: From Actor to Agent

In the traditional actor model, actors are passive entities—they wait for messages and react to them. While this simplifies concurrent programming by eliminating locks, actors themselves don't decide what to do next; they only respond to external stimuli.

POJO-actor's workflow engine changes this. By attaching a workflow to an actor, you give it complex behavioral patterns: conditional branching, loops, and state-driven decisions. The actor becomes an agent—an autonomous entity that observes its environment and acts according to its own logic.

With Virtual Threads since JDK 21, you can create tens of thousands of such autonomous agents. This combination—complex behavior per actor, massive scale—was impractical before and opens up new applications: large-scale agent-based simulations, infrastructure platforms that monitor and self-repair, and more.

> An agent is anything that can be viewed as perceiving its environment through sensors and acting upon that environment through actuators.
> — Russell & Norvig, "Artificial Intelligence: A Modern Approach"

### Workflow Format

Because the workflow is essentially a Turing machine, conditional branching and loops are expressed as state transitions. And because this is POJO-actor, each step is simply "send this message to this actor"—just three elements: `actor`, `method`, and `arguments`:

```yaml
- states: ["start", "processed"]
  actions:
    - actor: dataProcessor    # actor name
      method: process         # method name
      arguments: "data.csv"   # arguments
```

This follows the same mental model as `tell()`/`ask()` in Java code. The combination allows complex logic that traditional YAML-based workflow languages struggle with—without introducing custom syntax.

### Workflow Example: Turing Machine

The following is a Turing machine that outputs an irrational number. It outputs 001011011101111011111...

![](Turing87.jpg)

> — Charles Petzold, "The Annotated Turing", Wiley Publishing, Inc. (2008) page 87.

Using POJO-actor's workflow format:

```yaml
name: turing87
steps:
- states: ["0", "100"]
  actions:
  - {actor: turing, method: initMachine}
- states: ["100", "1"]
  actions:
  - {actor: turing, method: printTape}
- states: ["1", "2"]
  actions:
  - {actor: turing, method: put, arguments: "e"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: put, arguments: "e"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: put, arguments: "0"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: put, arguments: "0"}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
- states: ["101", "2"]
  actions:
  - {actor: turing, method: printTape}
- states: ["2", "2"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: "1"}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: put, arguments: "x"}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
- states: ["2", "3"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: "0"}
- states: ["3", "3"]
  actions:
  - {actor: turing, method: isAny}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: move, arguments: "R"}
- states: ["3", "4"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: put, arguments: "1"}
  - {actor: turing, method: move, arguments: "L"}
- states: ["4", "3"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: "x"}
  - {actor: turing, method: put, arguments: " "}
  - {actor: turing, method: move, arguments: "R"}
- states: ["4", "5"]
  actions:
  - {actor: turing, method: matchCurrentValue, arguments: "e"}
  - {actor: turing, method: move, arguments: "R"}
- states: ["4", "4"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
- states: ["5", "5"]
  actions:
  - {actor: turing, method: isAny}
  - {actor: turing, method: move, arguments: "R"}
  - {actor: turing, method: move, arguments: "R"}
- states: ["5", "101"]
  actions:
  - {actor: turing, method: isNone}
  - {actor: turing, method: put, arguments: "0"}
  - {actor: turing, method: move, arguments: "L"}
  - {actor: turing, method: move, arguments: "L"}
```

This example includes conditional branching using multiple transitions with the same from-state:

```yaml
# From state 2: if current value is "1", stay in state 2
- states: ["2", "2"]
  actions:
    - actor: turing
      method: matchCurrentValue
      arguments: "1"
    # ... subsequent actions

# From state 2: if current value is "0", go to state 3
- states: ["2", "3"]
  actions:
    - actor: turing
      method: matchCurrentValue
      arguments: "0"
```

- If `matchCurrentValue("1")` returns true → Execute first transition, remain in state 2
- If `matchCurrentValue("1")` returns false → Abort this transition, try next transition
- If `matchCurrentValue("0")` returns true → Transition to state 3

**Running the Example:**

```bash
git clone https://github.com/scivicslab/POJO-actor
cd POJO-actor
git checkout v2.12.1
mvn install

git clone https://github.com/scivicslab/actor-WF-examples
cd actor-WF-examples
git checkout v2.12.1
mvn compile
mvn exec:java -Dexec.mainClass="com.scivicslab.turing.TuringWorkflowApp" -Dexec.args="turing87"
```

**Output:**

```
Loading workflow from: /code/turing87.yaml
Workflow loaded successfully
Executing workflow...

TAPE    0    value
TAPE    0    value    ee0 0 1 0
TAPE    0    value    ee0 0 1 0 1 1 0
TAPE    0    value    ee0 0 1 0 1 1 0 1 1 1 0
TAPE    0    value    ee0 0 1 0 1 1 0 1 1 1 0 1 1 1 1 0

Workflow finished: Maximum iterations (200) exceeded
```

POJO-actor's workflow engine is based on the same design philosophy as Turing machines. Any complexity of processing can be expressed through combinations of state transitions and actions.



## Feature List

A comprehensive list of features provided by POJO-actor.

### Core
- **POJO Actor Model** — Turn any Java object into an actor
- **Virtual Threads** — Massive actor scalability with lightweight virtual threads
- **Managed Thread Pool** — Dedicated thread pool for CPU-intensive tasks
- **Job Cancellation** — Cancel pending jobs per actor
- **Immediate Execution** — Bypass message queues with tellNow/askNow
- **Actor Hierarchies** — Parent-child relationships for actor supervision

### Distributed
- **Distributed Actor System** — Inter-node communication via HTTP
- **Remote Actor Reference** — Transparent access to actors on remote nodes
- **Node Discovery** — Auto-detection for Slurm/Kubernetes/Grid Engine environments

### Workflow Engine
- **YAML Workflow** — Define workflows in YAML format
- **Subworkflows** — Split and reuse workflow definitions
- **YAML Overlay** — Environment-specific configuration (dev/staging/prod)

### Extensibility
- **Dynamic Actor Loading** — Load actors from external JARs at runtime
- **Plugin Architecture** — Register plugins via ServiceLoader
- **GraalVM Native Image** — Full support for native image compilation


## References

- **Official Documentation**: For detailed manuals and API references, visit the [POJO-actor documentation](https://scivicslab.com/docs/pojo-actor/introduction) on our homepage.

- **Javadoc**: [API Reference](https://scivicslab.github.io/POJO-actor/)

- **Design Background**: The core ideas behind POJO-actor are explained on CoderLegion: [POJO-actor v1.0: A Lightweight Actor Model Library for Java](https://coderlegion.com/8748/pojo-actor-v1-0-a-lightweight-actor-model-library-for-java)


## Acknowledgments

POJO-actor was inspired by Alexander Zakusylo's [`actr`](https://medium.com/@zakgof/type-safe-actor-model-for-java-7133857a9f72) library, which pioneered the POJO-based actor model approach in Java. While `actr` introduced many excellent concepts, POJO-actor extends and improves upon them with:

- **Message ordering guarantee**: Unlike `actr`, POJO-actor ensures that messages sent to an actor are processed in the order they were sent
- **Modern Java features**: Built with Java 21+ virtual threads and modern concurrency patterns
- **Enhanced thread pool management**: `actr` used real threads for actors, limiting scalability to CPU core count and causing performance issues with heavy computations. POJO-actor uses virtual threads for actors and delegates heavy computations to configurable managed thread pools, allowing thousands of actors while controlling CPU core usage


We acknowledge the foundational work done by the `actr` library team in making actor model programming more accessible to Java developers.

We also acknowledge [`Comedy.js`](https://github.com/untu/comedy), a Node.js actor framework, which inspired POJO-actor's basic architecture design, particularly the **ActorSystem** and **ActorRef** concepts. While Comedy.js uses one process or one real thread per actor, POJO-actor leverages Java's virtual threads to enable thousands of lightweight actors.


## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

