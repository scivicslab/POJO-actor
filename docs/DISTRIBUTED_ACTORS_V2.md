# Distributed Actors Architecture (v2.5.0)

## Overview

POJO-actor v2.5.0 introduces distributed actor capabilities, allowing actors to communicate across multiple nodes using lightweight HTTP-based messaging.

## Design Philosophy

### Why HTTP?
- **Lightweight**: Uses Java standard `com.sun.net.httpserver.HttpServer` (no external dependencies)
- **Simple**: REST API is easy to debug and monitor
- **Flexible**: Works with Slurm, Kubernetes, bare metal, or any network environment
- **Stateless**: Each HTTP request is independent, simplifying failure handling

### Why Not Kafka?
While Kafka provides excellent features (persistence, high throughput), it adds:
- Infrastructure complexity (separate Kafka cluster)
- Deployment overhead (especially in HPC environments like Slurm)
- Additional operational burden

For POJO-actor's use case, direct HTTP communication is simpler and sufficient.

## Architecture

```
Node 1 (host1:8081)                    Node 2 (host2:8082)
┌───────────────────────────┐         ┌───────────────────────────┐
│ DistributedActorSystem    │         │ DistributedActorSystem    │
│ ┌───────────────────────┐ │         │ ┌───────────────────────┐ │
│ │ HttpServer :8081      │ │         │ │ HttpServer :8082      │ │
│ │ POST /actor/*/invoke  │ │         │ │ POST /actor/*/invoke  │ │
│ └───────────────────────┘ │         │ └───────────────────────┘ │
│                           │         │                           │
│ Local Actors:             │         │ Local Actors:             │
│ - math (MathPlugin)       │◄────────│ HTTP POST                 │
│ - calculator              │         │ - logger                  │
│                           │         │ - monitor                 │
└───────────────────────────┘         └───────────────────────────┘
```

## Components

### 1. ActorMessage
JSON-serializable message protocol for actor invocation.

```java
{
  "actorName": "math",
  "actionName": "add",
  "args": "5,3",
  "messageId": "uuid-xxx",
  "replyTo": "node2:8082"  // Optional, for ask() pattern
}
```

### 2. NodeInfo
Registry entry for known nodes.

```java
class NodeInfo {
    String nodeId;
    String host;
    int port;

    String getAddress() {
        return host + ":" + port;
    }
}
```

### 3. DistributedActorSystem
Extended ActorSystem with HTTP server capabilities.

**Key Features:**
- Embeds `HttpServer` listening on specified port
- Registers local actors (IIActorRef instances)
- Maintains registry of remote nodes
- Routes incoming HTTP requests to local actors
- Provides RemoteActorRef for calling remote actors

**HTTP Endpoints:**
- `POST /actor/{actorName}/invoke` - Invoke an actor action

### 4. RemoteActorRef
Proxy for remote actor access.

**Key Features:**
- Implements same `callByActionName()` interface
- Translates calls to HTTP POST requests
- Handles JSON serialization/deserialization
- Supports both fire-and-forget (tell) and request-reply (ask)

## Usage Example

### Simple Two-Node Setup

**Node 1: Host math actor**
```java
DistributedActorSystem system1 = new DistributedActorSystem("node1", 8081);

// Register local actor
MathPlugin math = new MathPlugin();
MathIIAR mathActor = new MathIIAR("math", math, system1);
system1.addIIActor(mathActor);

System.out.println("Node1 ready on port 8081");
```

**Node 2: Call remote math actor**
```java
DistributedActorSystem system2 = new DistributedActorSystem("node2", 8082);

// Register remote node
system2.registerRemoteNode("node1", "localhost", 8081);

// Get remote actor reference
RemoteActorRef remoteMath = system2.getRemoteActor("node1", "math");

// Call remote actor
ActionResult result = remoteMath.callByActionName("add", "5,3");
System.out.println("Result: " + result.getResult()); // "8"
```

### Automatic Node Discovery

POJO-actor v2.5.0 includes automatic node discovery utilities for common distributed environments, eliminating the need to manually configure node addresses.

#### Using NodeDiscoveryFactory

**Simplest Approach** (fully automatic):
```java
import com.scivicslab.pojoactor.distributed.discovery.*;

public class MyActorApp {
    public static void main(String[] args) throws IOException {
        // Auto-detect environment and create system with all nodes registered
        DistributedActorSystem system = NodeDiscoveryFactory.createDistributedSystem(8080);

        // Register your actors
        MathPlugin math = new MathPlugin();
        system.addIIActor(new MathIIAR("math", math, system));

        // All remote nodes are already registered - ready to use!
        // ...
    }
}
```

**Manual Control** (if needed):
```java
// Detect environment
NodeDiscovery discovery = NodeDiscoveryFactory.autoDetect(8080);

// Get node information
String myNodeId = discovery.getMyNodeId();
String myHost = discovery.getMyHost();
int myPort = discovery.getMyPort();

// Create actor system
DistributedActorSystem system = new DistributedActorSystem(myNodeId, myHost, myPort);

// Register all remote nodes
for (NodeInfo node : discovery.getAllNodes()) {
    if (!node.getNodeId().equals(myNodeId)) {
        system.registerRemoteNode(node.getNodeId(), node.getHost(), node.getPort());
    }
}
```

#### Supported Environments

**1. Slurm** - Uses environment variables:
- `SLURM_JOB_NODELIST` - Node list (parsed via `scontrol show hostnames`)
- `SLURM_PROCID` - Current process rank

**2. Kubernetes StatefulSet** - Uses environment variables:
- `POD_NAME` - Current pod name (e.g., "pojo-actor-0")
- `SERVICE_NAME` - Headless service name
- `REPLICAS` - Number of replicas in StatefulSet
- `KUBERNETES_SERVICE_HOST` - Detects K8s environment

**3. Grid Engine** - Uses environment variables:
- `PE_HOSTFILE` - Path to hostfile with allocated nodes
- `SGE_TASK_ID` - Optional task ID for array jobs

### Slurm Deployment

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

### Kubernetes Deployment

```yaml
apiVersion: v1
kind: Service
metadata:
  name: actor-nodes
spec:
  clusterIP: None  # Headless service
  selector:
    app: pojo-actor
  ports:
  - port: 8080
---
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

## Message Protocol

### Request: Actor Invocation
```http
POST /actor/math/invoke HTTP/1.1
Host: node1:8081
Content-Type: application/json

{
  "actionName": "add",
  "args": "5,3",
  "messageId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### Response: Success
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "success": true,
  "result": "8",
  "messageId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### Response: Actor Not Found
```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "success": false,
  "result": "Actor 'math' not found on this node",
  "messageId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### Response: Action Execution Failed
```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "success": false,
  "result": "NumberFormatException: For input string: 'abc'",
  "messageId": "550e8400-e29b-41d4-a716-446655440000"
}
```

## Implementation Plan

### Phase 1: Core Infrastructure
1. `ActorMessage` - Message protocol class
2. `NodeInfo` - Node registry entry
3. `DistributedActorSystem` - HTTP server integration
4. `RemoteActorRef` - Remote actor proxy

### Phase 2: Testing
1. Single-node test (local HTTP)
2. Two-node communication test
3. Multi-node test (3+ nodes)
4. Error handling tests (network failure, actor not found)

### Phase 3: Advanced Features (Future)
1. Node discovery (multicast/gossip protocol)
2. Load balancing (round-robin across replicated actors)
3. Fault tolerance (retry logic, circuit breaker)
4. Monitoring (metrics endpoint, health checks)

## Benefits of This Approach

1. **Zero External Dependencies**: Uses only Java standard library
2. **Simple Deployment**: No middleware to manage
3. **Environment Agnostic**: Works with Slurm, Kubernetes, Docker, bare metal
4. **Easy Debugging**: HTTP requests are easy to inspect and test
5. **Gradual Migration**: Can mix local and remote actors transparently
6. **Native Image Ready**: No reflection in network layer (all uses CallableByActionName)

## Comparison with v2.0.0

| Feature | v2.0.0 | v2.5.0 |
|---------|--------|--------|
| Actor Model | Local only | Distributed |
| Communication | In-process | HTTP |
| Workflow Engine | Yes | Yes |
| Dynamic Loading | Yes | Yes |
| Multi-node | No | Yes |
| Deployment | Single JVM | Multi-node cluster |

## Next Steps

1. Implement core classes in `com.scivicslab.pojoactor.distributed` package
2. Add comprehensive tests
3. Create example applications (distributed calculator, map-reduce)
4. Update README.md with distributed examples
5. Performance benchmarking
