# Plugin registration — ActorProvider

A mechanism for auto-registering actors from an external JAR via the service loader.

---

## Implementation

```java
public class MyProvider implements ActorProvider {
    @Override
    public void registerActors(ActorSystem system) {
        system.actorOf("myActor", new MyActor());
    }
}
```

```
# META-INF/services/com.scivicslab.pojoactor.core.ActorProvider
com.example.MyProvider
```

Simply add the JAR to the classpath and `registerActors()` is called automatically when `ActorSystem` starts.

---

## DynamicActorLoader — Runtime loading

Dynamically loads an external JAR after startup to add actors at runtime.
The `loader.loadJar` / `loader.createChild` commands in Turing Workflow use this mechanism.

```java
DynamicActorLoader loader = new DynamicActorLoader(system);

// Load a JAR from the Maven local repository
loader.loadJar("com.example:my-plugin:1.0.0");

// Create an actor from a class in the loaded JAR
ActorRef<?> actor = loader.createActor("myActor", "com.example.MyActor");
```
