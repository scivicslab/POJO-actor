# POJO-actor

**Official Website: [scivicslab.com/docs/pojo-actor](https://scivicslab.com/docs/pojo-actor/introduction)**

A lightweight, GraalVM Native Image compatible actor model library for Java that turns ordinary POJOs (Plain Old Java Objects) into actors with minimal overhead.

[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Javadoc](https://img.shields.io/badge/javadoc-3.0.0-brightgreen.svg)](https://scivicslab.github.io/POJO-actor/)
[![Official Docs](https://img.shields.io/badge/docs-scivicslab.com-green.svg)](https://scivicslab.com/docs/pojo-actor/introduction)


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

```xml
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>pojo-actor</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Building from Source

```bash
git clone https://github.com/scivicslab/POJO-actor
cd POJO-actor
mvn install
```

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


## Workflow Engine: Turing-workflow

POJO-actor provides the actor model foundation. For workflow execution — YAML-based state machines that turn actors into autonomous agents — see [Turing-workflow](https://github.com/scivicslab/Turing-workflow).

[![Turing-workflow](https://img.shields.io/maven-central/v/com.scivicslab/turing-workflow.svg?label=turing-workflow)](https://central.sonatype.com/artifact/com.scivicslab/turing-workflow)



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

