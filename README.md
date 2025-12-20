# POJO-actor

A lightweight, GraalVM Native Image compatible actor model library for Java that turns ordinary POJOs (Plain Old Java Objects) into actors with minimal overhead.

[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Javadoc](https://img.shields.io/badge/javadoc-1.0.0-brightgreen.svg)](https://scivicslab.github.io/POJO-actor/)

## Architecture

POJO-actor implements a simplified actor model built on modern Java features.
Built with just ~800 lines of code, POJO-actor delivers a practical actor model implementation without sacrificing functionality or performance.

- **ActorSystem**: Manages actor lifecycle and configurable work-stealing thread pools
- **ActorRef**: Reference to an actor that provides `tell()` and `ask()` messaging interface
- **Virtual Threads**: Each actor runs on its own virtual thread for lightweight message handling
- **Work-Stealing Pools**: Heavy computations are delegated to configurable thread pools
- **Zero Reflection**: Built entirely with standard JDK APIs, making it GraalVM Native Image ready


## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>POJO-actor</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Note**: This library will be published to Maven Central soon. For now, please install it to your local repository first:

```bash
git clone https://github.com/scivicslab/POJO-actor
cd POJO-actor
./mvnw install
```

### Basic Usage

```java
import com.scivicslab.pojoactor.ActorSystem;
import com.scivicslab.pojoactor.ActorRef;

// Define your POJO
class Counter {
    private int count = 0;

    public void increment() {
        count++;
    }

    public int getValue() {
        return count;
    }
}

// Create actor system and actors
ActorSystem system = new ActorSystem("mySystem", 4); // 4 threads for CPU-intensive tasks
ActorRef<Counter> counter = system.actorOf("counter", new Counter());

// Send messages to actors
counter.tell(c -> c.increment());  // Fire-and-forget
CompletableFuture<Integer> result = counter.ask(c -> c.getValue());  // Request-response

// Get the result
int value = result.get(); // Returns 1

// Cleanup
system.terminate();
```

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

## Advanced Usage

### Parallel Matrix Multiplication

POJO-actor excels at parallel computation tasks. Here's an example of distributed matrix multiplication that demonstrates the proper use of work-stealing pools for heavy computations:

```java
// Create large matrices for multiplication
final int matrixSize = 400;
final int blockSize = 100;
double[][] matrixA = new double[matrixSize][matrixSize];
double[][] matrixB = new double[matrixSize][matrixSize];

// Create ActorSystem with 4 CPU threads for heavy computation
ActorSystem system = new ActorSystem("matrixSystem", 4);
List<CompletableFuture<Double>> futures = new ArrayList<>();

// Divide matrix into blocks and assign to different actors
for (int blockRow = 0; blockRow < 4; blockRow++) {
    for (int blockCol = 0; blockCol < 4; blockCol++) {
        MatrixCalculator calculator = new MatrixCalculator();
        ActorRef<MatrixCalculator> actor = system.actorOf(
            String.format("block_%d_%d", blockRow, blockCol), calculator);

        // Light operation: Initialize actor with block coordinates (uses virtual thread)
        actor.tell(calc -> calc.initBlock(matrixA, matrixB, blockRow, blockCol)).get();

        // Heavy computation: Matrix multiplication (uses work-stealing pool)
        CompletableFuture<Double> blockSum = actor.ask(
            calc -> calc.calculateBlock(), // CPU-intensive matrix multiplication
            system.getWorkStealingPool()   // Delegate to work-stealing pool
        );

        futures.add(blockSum);
    }
}

// Wait for all parallel calculations to complete
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
```

**Key Performance Points:**
- **Light operations**: `initBlock()` just sets references, so virtual threads are perfect
- **Heavy computation**: `calculateBlock()` does actual matrix multiplication, so work-stealing pool is essential
- **Virtual threads**: Handle only setter/getter level operations, almost no computation
- **CPU control**: Only 4 CPU threads handle all heavy computation, regardless of actor count
- **Responsiveness**: Actors remain responsive to light messages during heavy computation
- **Scalability**: Can create thousands of actors without exhausting system resources

### Custom Thread Pools

```java
ActorSystem system = new ActorSystem("system");

// Add additional work-stealing pools
system.addWorkStealingPool(8);  // CPU-intensive tasks
system.addWorkStealingPool(2);  // I/O-bound tasks

// Use specific thread pools
counter.tell(c -> c.increment(), system.getWorkStealingPool(1));
```

### Actor Hierarchies

```java
ActorRef<ParentActor> parent = system.actorOf("parent", new ParentActor());
ActorRef<ChildActor> child = parent.createChild("child", new ChildActor());

// Parent can supervise child actors
Set<String> children = parent.getNamesOfChildren();
```


## GraalVM Native Image Support

Traditional actor model frameworks rely heavily on reflection for message routing, serialization, and dynamic proxy generation, making them incompatible with GraalVM Native Image compilation. These frameworks require extensive configuration files and reflection hints to work with native compilation, if at all.

POJO-actor is built using only modern JDK features, making the library itself very simple and easy to understand. It uses no reflection whatsoever. As a result, code written with POJO-actor compiles seamlessly to GraalVM Native Images without any obstacles:

```bash
# Compile to native image
native-image -jar target/POJO-actor-1.0.0-fat.jar -o pojo-actor-native

# Run native executable
./pojo-actor-native
```

No additional configuration files or reflection hints are required.

## Performance

- **Startup Time**: Near-instant with native compilation
- **Memory Usage**: Minimal heap allocation due to POJO-based design
- **Throughput**: High message processing rates with virtual threads
- **Scalability**: Efficient work-stealing thread pools for parallel tasks

### Performance Best Practices

For optimal performance, it's crucial to understand when to use virtual threads vs. work-stealing pools:

#### Light Operations - Use Default Virtual Threads
```java
// Fast operations that don't block or consume much CPU
counter.tell(c -> c.increment());
counter.tell(c -> c.setName("newName"));
listActor.tell(list -> list.add("item"));

CompletableFuture<Integer> size = listActor.ask(list -> list.size());
```

#### Heavy Computations - Delegate to Work-Stealing Pools
```java
ActorSystem system = new ActorSystem("system", 4); // 4 CPU threads for heavy work

// CPU-intensive calculations should use work-stealing pool
CompletableFuture<Double> result = calculator.ask(c -> c.performMatrixMultiplication(),
                                                 system.getWorkStealingPool());

// I/O operations or blocking calls should also use work-stealing pool
CompletableFuture<String> data = dataProcessor.ask(p -> p.readLargeFile(),
                                                   system.getWorkStealingPool());
```

#### Why This Matters
- **Virtual threads** are perfect for lightweight message passing and state changes
- **Work-stealing pools** handle CPU-intensive tasks without blocking virtual threads
- This separation prevents heavy computations from affecting the actor system's responsiveness
- You can control CPU core usage by configuring the work-stealing pool size

```java
// Example: Mixed workload with proper thread pool usage
ActorSystem system = new ActorSystem("mixedSystem", 4);
ActorRef<DataProcessor> processor = system.actorOf("processor", new DataProcessor());

// Light operation - uses virtual thread
processor.tell(p -> p.updateCounter());

// Heavy operation - uses work-stealing pool
CompletableFuture<ProcessResult> heavyResult = processor.ask(
    p -> p.performComplexAnalysis(largeDataset),
    system.getWorkStealingPool()
);

// The actor remains responsive to light messages while heavy computation runs in background
processor.tell(p -> p.logStatus()); // This won't be blocked by heavy computation
```

## Requirements

- Java 21 or higher
- Maven 3.6+

## Dependencies

- **Runtime**: JDK standard library only
- **Testing**: JUnit 5, Apache Commons Math (test scope only)

## Building

```bash
# Compile and test
mvn clean test

# Build JAR
mvn clean package

# Generate Javadoc
mvn javadoc:javadoc
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## Acknowledgments

POJO-actor was inspired by Alexander Zakusylo's [`actr`](https://medium.com/@zakgof/type-safe-actor-model-for-java-7133857a9f72) library, which pioneered the POJO-based actor model approach in Java. While `actr` introduced many excellent concepts, POJO-actor extends and improves upon them with:

- **Message ordering guarantee**: Unlike `actr`, POJO-actor ensures that messages sent to an actor are processed in the order they were sent
- **Modern Java features**: Built with Java 21+ virtual threads and modern concurrency patterns
- **Enhanced thread pool management**: `actr` used real threads for actors, limiting scalability to CPU core count and causing performance issues with heavy computations. POJO-actor uses virtual threads for actors and delegates heavy computations to configurable work-stealing pools, allowing thousands of actors while controlling CPU core usage


We acknowledge the foundational work done by the `actr` library team in making actor model programming more accessible to Java developers.

We also acknowledge [`Comedy.js`](https://github.com/untu/comedy), a Node.js actor framework, which inspired POJO-actor's basic architecture design, particularly the **ActorSystem** and **ActorRef** concepts. While Comedy.js uses one process or one real thread per actor, POJO-actor leverages Java's virtual threads to enable thousands of lightweight actors.

## Future Plans

- `tellNow`: add an API that can bypass the actor's virtual-thread mailbox and execute a message immediately when the caller needs synchronous semantics.
- Clear pending messages: provide a utility that resets an actor's message queue to simplify test setups and restart scenarios.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
