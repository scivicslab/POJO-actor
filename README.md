# POJO-actor

A lightweight, GraalVM Native Image compatible actor model library for Java that turns ordinary POJOs (Plain Old Java Objects) into actors with minimal overhead. 

[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Javadoc](https://img.shields.io/badge/javadoc-2.6.0-brightgreen.svg)](https://scivicslab.github.io/POJO-actor/)

## Architecture

POJO-actor implements a practical actor model built on modern Java features with a focus on production use.

- **ActorSystem**: Manages actor lifecycle and configurable worker pools for CPU-bound tasks
- **ActorRef**: Reference to an actor that provides `tell()`, `ask()`, `tellNow()`, and `askNow()` messaging interface
- **Virtual Threads**: Each actor runs on its own virtual thread for lightweight message handling
- **WorkerPool Interface**: Abstracts thread pool implementations (ControllableWorkStealingPool or ForkJoinPool)
- **Job Cancellation**: Cancel pending CPU-bound jobs per actor with `clearPendingMessages()`
- **Zero Reflection Core**: Core features built entirely with standard JDK APIs, making them GraalVM Native Image ready

### Key Features in v2.0.0

- **WorkerPool Abstraction**: Choose between ControllableWorkStealingPool (default, with job cancellation) or ForkJoinPoolWrapper (legacy work-stealing)
- **Actor-Level Job Management**: Track and cancel CPU-bound jobs per actor independently
- **Immediate Execution**: `tellNow()` and `askNow()` bypass message queues for urgent operations
- **Dynamic Actor Loading**: Load actors from external JARs at runtime using standard URLClassLoader
- **Workflow Engine**: Integrated YAML/JSON-based workflow orchestration for data-driven actor coordination
- **Plugin Architecture**: ServiceLoader-based plugin system for automatic actor registration
- **Production Ready**: Enhanced error handling and resource management for real-world applications

### Key Features in v2.6.0

- **XML Workflow Support**: Define workflows in XML format alongside YAML/JSON
- **XSLT Transformation**: Convert XML workflows to beautiful HTML visualizations (table and graph views)
- **Enhanced Workflow Engine**: Improved workflow documentation and tooling

### Key Features in v2.5.0

- **Distributed Actor System**: Actors can communicate across multiple nodes using lightweight HTTP
- **Zero External Dependencies**: Uses only Java standard `HttpServer` (no Kafka, no middleware)
- **Simple Deployment**: Works with Slurm, Kubernetes, bare metal, or any network environment
- **Remote Actor References**: Transparent access to actors on remote nodes
- **String-Based Protocol**: All messages are JSON-serializable for network transmission
- **Native Image Compatible**: No reflection in network layer, fully compatible with GraalVM


## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>POJO-actor</artifactId>
    <version>2.6.0</version>
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

### Distributed Actors (v2.5.0)

POJO-actor v2.5.0 introduces distributed actor capabilities, allowing actors to communicate across multiple nodes using lightweight HTTP. Each node runs an embedded HTTP server, enabling remote actor invocation without external middleware.

#### Two-Node Example

**Node 1: Host math actor**
```java
import com.scivicslab.pojoactor.distributed.*;
import com.scivicslab.pojoactor.workflow.*;
import com.scivicslab.pojoactor.*;

// Create distributed actor system on Node 1
DistributedActorSystem system1 = new DistributedActorSystem("node1", "192.168.1.10", 8081);

// Register local actor
MathPlugin math = new MathPlugin();
MathIIAR mathActor = new MathIIAR("math", math, system1);
system1.addIIActor(mathActor);

System.out.println("Node 1 ready on 192.168.1.10:8081");
```

**Node 2: Call remote math actor**
```java
// Create distributed actor system on Node 2
DistributedActorSystem system2 = new DistributedActorSystem("node2", "192.168.1.11", 8082);

// Register remote node
system2.registerRemoteNode("node1", "192.168.1.10", 8081);

// Get remote actor reference
RemoteActorRef remoteMath = system2.getRemoteActor("node1", "math");

// Call remote actor (sends HTTP POST to node1:8081/actor/math/invoke)
ActionResult result = remoteMath.callByActionName("add", "5,3");
System.out.println("Result: " + result.getResult()); // Prints: Result: 8
```

#### How It Works

1. **HTTP Server**: Each `DistributedActorSystem` runs an embedded `HttpServer` on its specified port
2. **Actor Registry**: Nodes maintain a registry of remote nodes and their addresses
3. **Message Protocol**: Actor invocations are converted to JSON and sent via HTTP POST
4. **String-Based Actions**: All messages use `CallableByActionName` interface (no reflection at runtime)

#### Automatic Node Discovery

POJO-actor provides automatic node discovery for common distributed environments. Instead of manually registering each node, use `NodeDiscoveryFactory` to detect and configure nodes automatically:

**Simple Example (Auto-Detection)**:
```java
import com.scivicslab.pojoactor.distributed.discovery.*;

public class MyActorApp {
    public static void main(String[] args) throws IOException {
        // Automatically detect environment (Slurm/Kubernetes/Grid Engine)
        // and create system with all nodes registered
        DistributedActorSystem system = NodeDiscoveryFactory.createDistributedSystem(8080);

        // Register your actors
        MathPlugin math = new MathPlugin();
        system.addIIActor(new MathIIAR("math", math, system));

        // All remote nodes are already registered - ready to use!
    }
}
```

**Supported Environments**:
- **Slurm**: Reads `SLURM_JOB_NODELIST` and `SLURM_PROCID` environment variables
- **Kubernetes**: Reads `POD_NAME`, `SERVICE_NAME`, and `REPLICAS` from StatefulSet
- **Grid Engine**: Reads `PE_HOSTFILE` from parallel environment

**Manual Discovery** (if you need more control):
```java
// Detect environment and get node info
NodeDiscovery discovery = NodeDiscoveryFactory.autoDetect(8080);

// Create actor system for this node
DistributedActorSystem system = new DistributedActorSystem(
    discovery.getMyNodeId(),
    discovery.getMyHost(),
    discovery.getMyPort()
);

// Register all remote nodes
for (NodeInfo node : discovery.getAllNodes()) {
    if (!node.getNodeId().equals(discovery.getMyNodeId())) {
        system.registerRemoteNode(
            node.getNodeId(),
            node.getHost(),
            node.getPort()
        );
    }
}
```

#### Deployment Examples

**Slurm (HPC Environment)**:
```bash
#!/bin/bash
#SBATCH --nodes=10
#SBATCH --ntasks-per-node=1

# Generate node list
NODE_LIST=$(scontrol show hostnames $SLURM_JOB_NODELIST | \
            awk '{print $1":8080"}' | paste -sd,)

# Launch on each node
srun java -jar myapp.jar \
  --node.id=node-$SLURM_PROCID \
  --node.port=8080 \
  --seed.nodes=$NODE_LIST
```

**Kubernetes**:
```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: pojo-actor
spec:
  serviceName: actor-nodes
  replicas: 10
  template:
    spec:
      containers:
      - name: actor-node
        image: myapp:v2
        env:
        - name: NODE_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: NODE_PORT
          value: "8080"
        ports:
        - containerPort: 8080
```

#### Benefits

- **No Middleware**: No Kafka, no Redis, no external message broker required
- **Simple Deployment**: Works in any network environment (HPC, cloud, bare metal)
- **Lightweight**: Uses Java standard library `HttpServer` (zero external dependencies)
- **Debuggable**: HTTP requests are easy to inspect and test with curl
- **Scalable**: Add/remove nodes dynamically without coordination
- **Native Image Ready**: No reflection in network communication layer

For detailed distributed actor documentation, see [docs/DISTRIBUTED_ACTORS_V2.md](docs/DISTRIBUTED_ACTORS_V2.md).

### Workflow Engine (YAML/JSON-Based Actor Orchestration)

POJO-actor v2.0.0 includes an integrated workflow engine that allows you to define and execute actor-based workflows using YAML or JSON files. This enables data-driven, configuration-based actor orchestration without hardcoding workflow logic.

#### Key Components

- **`IIActorSystem`**: Extended ActorSystem that manages workflow-compatible actors
- **`IIActorRef`**: Actor reference that can be invoked by string-based action names
- **`Interpreter`**: Workflow execution engine that reads YAML/JSON and orchestrates actors
- **`CallableByActionName`**: Interface that plugins implement for string-based invocation

#### Basic Workflow Example

**1. Define Workflow-Compatible Actor:**

```java
import com.scivicslab.pojoactor.workflow.*;
import com.scivicslab.pojoactor.*;

// Your actor implements CallableByActionName
public class DataProcessor implements CallableByActionName {
    private int processedCount = 0;

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        switch (actionName) {
            case "process":
                processedCount++;
                return new ActionResult(true, "Processed: " + args);

            case "getCount":
                return new ActionResult(true, String.valueOf(processedCount));

            default:
                return new ActionResult(false, "Unknown action: " + actionName);
        }
    }
}
```

**2. Create Workflow-Compatible Actor Reference:**

```java
// Create IIActorRef (Interpreter-Interfaced ActorRef)
public class DataProcessorIIAR extends IIActorRef<DataProcessor> {

    public DataProcessorIIAR(String actorName, DataProcessor object, IIActorSystem system) {
        super(actorName, object, system);
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        try {
            // Delegate to the actor object
            return this.ask(obj -> obj.callByActionName(actionName, args)).get();
        } catch (Exception e) {
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }
}
```

**3. Define Workflow in YAML:**

```yaml
# workflow.yaml
name: DataProcessingWorkflow

matrix:
  - states: ["0", "1"]
    actions:
      - [processor, process, "data1.csv"]
      - [processor, process, "data2.csv"]

  - states: ["1", "2"]
    actions:
      - [processor, getCount, ""]
      - [logger, log, "Processing complete"]

  - states: ["2", "end"]
    actions:
      - [system, shutdown, ""]
```

**4. Execute Workflow:**

```java
// Create workflow-compatible actor system
IIActorSystem system = new IIActorSystem("workflowSystem");

// Register actors
DataProcessorIIAR processor = new DataProcessorIIAR(
    "processor",
    new DataProcessor(),
    system
);
system.addIIActor(processor);

// Create and configure interpreter
Interpreter interpreter = new Interpreter.Builder()
    .loggerName("WorkflowInterpreter")
    .team(system)
    .build();

// Load workflow from YAML
try (InputStream input = new FileInputStream("workflow.yaml")) {
    interpreter.readYaml(input);
}

// Execute workflow step by step
ActionResult result = interpreter.execCode();  // Executes state 0 -> 1
System.out.println("Step 1: " + result.getResult());

result = interpreter.execCode();  // Executes state 1 -> 2
System.out.println("Step 2: " + result.getResult());

result = interpreter.execCode();  // Executes state 2 -> end
System.out.println("Step 3: " + result.getResult());

system.terminate();
```

#### Workflow Format

Each workflow consists of a **matrix** of rows, where each row defines:
- **states**: `[currentState, nextState]` - State transition
- **actions**: List of `[actorName, actionName, arguments]` - Actions to execute

**State Transitions:**
```
[0] ---> [1] ---> [2] ---> [end]
  |        |        |
  actions  actions  actions
```

#### Benefits of Workflow Engine

- **Configuration-Driven**: Change behavior without recompiling
- **Visual Workflow Design**: YAML/JSON files are human-readable
- **Distributed System Ready**: All messages are strings (network-serializable)
- **Hot Reload**: Load new workflows at runtime
- **Testing**: Easy to test workflows in isolation
- **Version Control**: Workflows are text files (git-friendly)

#### JSON Workflow Format

You can also use JSON instead of YAML:

```json
{
  "name": "DataProcessingWorkflow",
  "matrix": [
    {
      "states": ["0", "1"],
      "actions": [
        ["processor", "process", "data1.csv"],
        ["processor", "process", "data2.csv"]
      ]
    },
    {
      "states": ["1", "end"],
      "actions": [
        ["processor", "getCount", ""]
      ]
    }
  ]
}
```

```java
// Load from JSON
try (InputStream input = new FileInputStream("workflow.json")) {
    interpreter.readJson(input);
}
```

#### Use Cases

- **Data Pipelines**: ETL workflows with multiple processing stages
- **State Machines**: Complex state transitions with side effects
- **Business Processes**: Multi-step approval workflows
- **Batch Processing**: Scheduled jobs with dependencies
- **Integration Flows**: Coordinating multiple services/actors

## GraalVM Native Image Support

Traditional actor model frameworks rely heavily on reflection for message routing, serialization, and dynamic proxy generation, making them incompatible with GraalVM Native Image compilation. These frameworks require extensive configuration files and reflection hints to work with native compilation, if at all.

### Core Features: Fully Compatible ✅

**POJO-actor's core features** are built using only modern JDK features with **zero reflection**, making them seamlessly compatible with GraalVM Native Images:

- ActorSystem, ActorRef
- WorkerPool (ControllableWorkStealingPool, ForkJoinPoolWrapper)
- tellNow(), askNow()
- Job cancellation (clearPendingMessages)

```bash
# Compile to native image
native-image -jar target/POJO-actor-2.0.0-fat.jar -o pojo-actor-native

# Run native executable
./pojo-actor-native
```

**No additional configuration files or reflection hints are required for core features.**

### Dynamic Actor Loading: Improved Compatibility ✅

The **Dynamic Actor Loader** feature (optional, new in v2.0.0) uses the **actor-WF approach** with string-based action invocation, significantly improving Native Image compatibility compared to traditional reflection-based approaches.

**How it works**:
- **Plugin Loading**: Uses URLClassLoader to load external JARs (one-time reflection for class instantiation)
- **Action Invocation**: Uses `CallableByActionName` interface with **switch statements** (zero reflection at runtime)
- **String-Based Messaging**: All actions are invoked via action names and string arguments

**Native Image Compatibility**:

| Component | Reflection Used | Frequency | Native Image Support |
|-----------|----------------|-----------|---------------------|
| Class Loading | ✅ Yes (Class.newInstance) | Once per plugin | Configurable via reflect-config.json |
| Action Invocation | ❌ No (switch statement) | Every call | ✅ Fully compatible |

**Example: Reflection-Free Action Invocation**
```java
// Plugin implements CallableByActionName
public class MathPlugin implements CallableByActionName {
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        switch (actionName) {  // ← No reflection, Native Image friendly!
            case "add": return handleAdd(args);
            case "multiply": return handleMultiply(args);
            default: return new ActionResult(false, "Unknown action");
        }
    }
}

// String-based invocation (serializable, network-ready)
mathActor.tell(m -> m.callByActionName("add", "5,3"));
```

**Benefits for Native Image**:
- ✅ **Action invocation is reflection-free**: switch statements compile to native code
- ✅ **Minimal configuration**: Only need to register plugin class constructors, not individual methods
- ✅ **Distributed system ready**: String-based messages can be sent across network boundaries
- ✅ **YAML/JSON workflow support**: Actions can be defined in external configuration files

**Configuration**:
```json
// reflect-config.json (minimal, only for class loading)
[
  {
    "name": "com.example.MathPlugin",
    "methods": [{"name": "<init>", "parameterTypes": []}]
  }
]
```

**Conclusion**: With the actor-WF approach, Dynamic Actor Loading is **Native Image compatible** for runtime action invocation. Only the initial plugin class loading requires minimal reflection configuration. For complete dynamic loading from external JARs, JIT mode is still recommended.

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

### Core Features
- **Runtime**: JDK 21+ standard library only (zero external dependencies)

### Workflow Engine (optional)
- **SnakeYAML 1.33**: YAML parsing for workflow definitions
- **Jackson Databind 2.13.0**: JSON parsing for workflow definitions

### Testing
- **JUnit 5**: Unit testing framework
- **Apache Commons Math 3.6.1**: Matrix operations in performance tests (test scope only)

## Building

```bash
# Compile and test
mvn clean test

# Build JAR
mvn clean package

# Generate Javadoc and copy to docs/ directory for GitHub Pages
mvn clean verify

# Or generate Javadoc only (without copying to docs/)
mvn javadoc:javadoc
```

**Note**: The `mvn verify` command automatically generates Javadoc and copies it to the `docs/` directory, which is used by GitHub Pages to serve the [online documentation](https://scivicslab.github.io/POJO-actor/).

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

## What's New in v2.0.0

### Immediate Execution APIs
- **`tellNow()` / `askNow()`**: Bypass message queues for urgent operations that need immediate execution
- Execute concurrent queries while long-running tasks are in progress
- Perfect for state queries, emergency stop commands, and high-priority operations

### WorkerPool Job Management
- **WorkerPool Interface**: Abstraction layer supporting multiple thread pool implementations
- **ControllableWorkStealingPool**: Default implementation with per-actor job tracking and cancellation
- **ForkJoinPoolWrapper**: Legacy work-stealing pool for backward compatibility
- **Job Cancellation**: `clearPendingMessages()` now cancels both message queue and WorkerPool jobs

### Example: Cancel CPU-Bound Jobs
```java
ActorSystem system = new ActorSystem("system", 4);
ActorRef<DataProcessor> processor = system.actorOf("processor", new DataProcessor());

// Submit 100 CPU-bound jobs
for (int i = 0; i < 100; i++) {
    processor.tell(p -> p.heavyComputation(), system.getWorkStealingPool());
}

// Cancel remaining jobs if error occurs
int cancelled = processor.clearPendingMessages();
// ✅ Both message queue and WorkerPool jobs are cancelled!
```

### Dynamic Actor Loading (actor-WF Approach)
- **Runtime Extensibility**: Load actors from external JARs without restarting the application
- **String-Based Action Invocation**: Use `CallableByActionName` interface for reflection-free method calls
- **Distributed System Ready**: All messages are string-based, enabling network serialization
- **YAML/JSON Workflows**: Execute actions defined in external configuration files
- **ServiceLoader Integration**: Automatic actor registration via Java's ServiceLoader mechanism
- **Native Image Compatible**: Action invocation uses switch statements (zero reflection at runtime)

### Example: Load Actor from External JAR
```java
// Load a plugin class from external JAR and create an actor
Path pluginJar = Paths.get("plugins/math-plugin.jar");
ActorRef<MathPlugin> mathActor = DynamicActorLoader.loadActor(
    pluginJar,
    "com.example.plugin.MathPlugin",
    "mathActor"
);

// String-based action invocation (no reflection, serializable)
CompletableFuture<ActionResult> addResult = mathActor.ask(m ->
    m.callByActionName("add", "5,3")
);

ActionResult outcome = addResult.get();
if (outcome.isSuccess()) {
    System.out.println("Result: " + outcome.getResult()); // Prints: Result: 8
}

// Workflow-like sequential execution
mathActor.ask(m -> m.callByActionName("add", "10,5")).get();
mathActor.ask(m -> m.callByActionName("multiply", "3,4")).get();
ActionResult finalResult = mathActor.ask(m -> m.callByActionName("getLastResult", "")).get();
System.out.println("Final: " + finalResult.getResult()); // Prints: Final: 12
```

**Why actor-WF approach?**
- ✅ No reflection overhead during action invocation
- ✅ Messages can be sent across network boundaries
- ✅ Foundation for future distributed actor systems
- ✅ GraalVM Native Image compatible (action execution)
- ✅ YAML/JSON workflow engines can invoke actions directly

### Workflow Engine Integration

POJO-actor v2.0.0 integrates the actor-WF workflow engine, enabling YAML/JSON-based actor orchestration:

- **`IIActorSystem`**: Extended ActorSystem for workflow-compatible actors
- **`IIActorRef`**: Actor references callable by string-based action names
- **`Interpreter`**: YAML/JSON workflow execution engine
- **State Machine Workflows**: Define complex state transitions in external files
- **Data-Driven Execution**: Change workflow behavior without recompiling

```yaml
# Example: workflow.yaml
name: DataPipeline
matrix:
  - states: ["0", "1"]
    actions:
      - [processor, process, "data.csv"]
      - [validator, validate, ""]
  - states: ["1", "end"]
    actions:
      - [reporter, generate, "report.pdf"]
```

This foundation enables future distributed actor systems where workflows can span multiple nodes.

### Architecture Changes
- **Direction Shift**: From minimal implementation (~800 lines) to production-ready features
- **Enhanced Control**: Per-actor job management prevents resource waste
- **Workflow Integration**: Actor-WF engine merged into core for unified workflow execution
- **Plugin System**: Runtime-extensible architecture for modular applications
- **Proven Performance**: Tests show 80% job cancellation rate (80 out of 100 jobs cancelled)

## What's New in v2.6.0

### XML Workflow Support

POJO-actor v2.6.0 adds **XML workflow format** as an alternative to YAML and JSON:

- **`Interpreter.readXml()`**: Parse XML workflow definitions
- **Clean XML Format**: Attribute-based syntax with text content for arguments
- **Human-Readable**: Less nested than JSON, more structured than YAML
- **Full Compatibility**: Same internal `MatrixCode` structure as YAML/JSON

#### XML Workflow Example

```xml
<?xml version="1.0" encoding="UTF-8"?>
<workflow name="data-processing">
    <matrix>
        <transition from="init" to="processing">
            <action actor="processor" method="loadData">/data/input.csv</action>
        </transition>

        <transition from="processing" to="end">
            <action actor="processor" method="analyze"></action>
            <action actor="logger" method="log">Processing complete</action>
        </transition>
    </matrix>
</workflow>
```

### XSLT Transformation to HTML

New feature: Transform XML workflows to beautiful HTML visualizations!

- **`WorkflowXsltTransformer`**: Java utility for XSLT transformations
- **Table View**: Structured table format showing all transitions
- **Graph View**: Visual state transition graph with modern styling
- **Self-Contained HTML**: Embedded CSS, opens directly in browsers

#### Example Usage

```java
// Transform XML workflow to HTML table
File xmlFile = new File("workflow.xml");
File htmlFile = new File("workflow-table.html");
WorkflowXsltTransformer.transformToTable(xmlFile, htmlFile);

// Transform to graph view
File graphFile = new File("workflow-graph.html");
WorkflowXsltTransformer.transformToGraph(xmlFile, graphFile);

// Transform to String
String html = WorkflowXsltTransformer.transformToTableString(xmlFile);
```

#### Benefits

- **Documentation**: Auto-generate workflow documentation
- **Team Review**: Share visual workflows with non-technical stakeholders
- **Debugging**: Visualize complex workflows for easier understanding
- **Version Control**: Track workflow changes with visual diffs

### All Three Formats Supported

POJO-actor now supports three workflow formats, all using the same internal structure:

| Format | Extension | Best For |
|--------|-----------|----------|
| YAML | .yaml, .yml | Quick prototyping, simple workflows |
| JSON | .json | API integration, programmatic generation |
| XML | .xml | Complex workflows, HTML visualization, enterprise standards |

```java
// All three work the same way
interpreter.readYaml(yamlInput);
interpreter.readJson(jsonInput);
interpreter.readXml(xmlInput);
```

## What's New in v2.5.0

### Distributed Actor System

POJO-actor v2.5.0 introduces **distributed actor capabilities** for multi-node deployments:

- **`DistributedActorSystem`**: Extended actor system with embedded HTTP server
- **`RemoteActorRef`**: Proxy for transparent remote actor invocation
- **`ActorMessage`**: JSON-serializable message protocol
- **`NodeInfo`**: Node registry for tracking remote actors

#### Key Design Decisions

**Why HTTP instead of Kafka?**

After considering Apache Kafka for distributed messaging, we chose **lightweight HTTP** for these reasons:

- **Deployment Simplicity**: No external middleware (Kafka cluster) to manage
- **HPC Compatibility**: Works seamlessly with Slurm and batch scheduling systems
- **Universal Support**: HTTP works everywhere (Kubernetes, bare metal, cloud, HPC)
- **Operational Simplicity**: No additional infrastructure to maintain
- **Debugging**: Easy to test with curl, inspect traffic with standard tools

**HTTP is sufficient because:**
- Actor systems already handle message ordering per actor
- For distributed workloads, eventual consistency is acceptable
- Stateless HTTP eliminates complex failure scenarios
- Java's `HttpServer` is lightweight and zero-dependency

### Example: Distributed Math Computation

```java
// Node 1: Host computation actors
DistributedActorSystem node1 = new DistributedActorSystem("compute-1", "10.0.1.10", 8080);
MathPlugin math = new MathPlugin();
node1.addIIActor(new MathIIAR("math", math, node1));

// Node 2: Coordinate distributed work
DistributedActorSystem node2 = new DistributedActorSystem("coordinator", "10.0.1.11", 8080);
node2.registerRemoteNode("compute-1", "10.0.1.10", 8080);

// Call remote actor from coordinator
RemoteActorRef remoteMath = node2.getRemoteActor("compute-1", "math");
ActionResult result = remoteMath.callByActionName("multiply", "999,888");
System.out.println("Computed on remote node: " + result.getResult());

// Health check
// GET http://10.0.1.10:8080/health
// {"nodeId":"compute-1","status":"healthy","actors":1}
```

### Native Image Compatibility

All distributed features are **GraalVM Native Image ready**:
- ✅ HTTP server: Uses standard `com.sun.net.httpserver.HttpServer`
- ✅ JSON serialization: Uses Jackson (widely supported in Native Image)
- ✅ Actor invocation: Zero reflection (uses `CallableByActionName` interface)
- ✅ Message protocol: All strings, no dynamic class loading at runtime

### Performance & Scalability

- **10+ nodes tested**: Successfully tested with multi-node communication
- **Lightweight**: HTTP overhead is negligible for actor workloads
- **Concurrent**: Each node handles multiple connections via virtual threads
- **Fault Tolerant**: Node failures don't affect other nodes (no central coordinator)

### Deployment Flexibility

Works out-of-the-box with:
- **Slurm**: HPC batch scheduling systems
- **Kubernetes**: Cloud-native orchestration
- **Docker Compose**: Local multi-container development
- **Bare Metal**: Traditional server deployments

See [docs/DISTRIBUTED_ACTORS_V2.md](docs/DISTRIBUTED_ACTORS_V2.md) for complete distributed actor documentation.

## Future Plans

- **Node Discovery**: Automatic node registration via multicast or gossip protocol
- **Load Balancing**: Distribute actor instances across multiple nodes
- **Fault Tolerance**: Automatic failover and actor migration
- **Priority Execution**: `tellNow(action, pool)` for urgent CPU-bound jobs
- **Message Queue Migration**: Move to LinkedBlockingDeque for priority-based message processing
- **Dead Letter Handling**: Route failed messages to dead letter queues
- **Metrics Collection**: Built-in statistics for message processing and queue sizes
- **Actor Replication**: Create redundant copies of critical actors

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
