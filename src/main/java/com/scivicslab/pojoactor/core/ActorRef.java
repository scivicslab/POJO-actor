/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.scivicslab.pojoactor.core;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;



/**
 * A reference to an actor that provides messaging capabilities and lifecycle management.
 * This class implements the actor model pattern, allowing asynchronous message passing
 * between actors using tell() and ask() methods.
 *
 * @param <T> the type of the actor's underlying object
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class ActorRef<T> implements AutoCloseable {

    /** A logger for the ActorRef.
     * In order to be able to change the name of the logger later,
     * it is not made static here.
     */
    private Logger logger = Logger.getLogger(ActorSystem.class.getName());

    protected final String actorName;

    protected volatile T object;

    protected ActorSystem actorSystem = null;

    // Custom message queue management
    private final BlockingQueue<Runnable> messageQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread actorThread;

    // CopyOnWriteArraySet preserves insertion order (unlike ConcurrentSkipListSet which sorts)
    CopyOnWriteArraySet<String> NamesOfChildren = new CopyOnWriteArraySet<>();
    String parentName;

    // === JSON State (lazy-initialized) ===
    private volatile JsonState jsonState;

    /**
     * Key used to store the last action result in JSON state.
     * Can be referenced as ${result} in variable expansion.
     * @since 2.14.0
     */
    private static final String LAST_RESULT_KEY = "result";


    /**
     * Constructs an ActorRef with the specified name and object.
     * 
     * @param actorName the unique name for this actor
     * @param object the object that will handle messages for this actor
     */
    public ActorRef(String actorName, T object) {
        this.actorName = actorName;
        this.object = object;
        this.actorThread = startMessageLoop();
    }

    
    /**
     * Constructs an ActorRef with the specified name, object, and actor system.
     * 
     * @param actorName the unique name for this actor
     * @param object the object that will handle messages for this actor
     * @param actorSystem the actor system this actor belongs to
     */
    public ActorRef(String actorName, T object, ActorSystem actorSystem) {
        this.actorName = actorName;
        this.object = object;
        this.actorSystem = actorSystem;
        this.actorThread = startMessageLoop();
    }


    /**
     * Creates a child actor under this actor's supervision.
     * 
     * @param <K> the type of the child actor's object
     * @param actorName the unique name for the child actor
     * @param object the object for the child actor
     * @return a reference to the created child actor
     */
    public  <K> ActorRef<K> createChild(String actorName, K object) {
        ActorRef<K> child = this.actorSystem.actorOf(actorName, object);
        child.setParentName(this.actorName);
        this.NamesOfChildren.add(actorName);

        return child;
    }


    /**
     * Returns the names of all child actors supervised by this actor.
     *
     * <p>The returned set preserves the order in which children were added.</p>
     *
     * @return a set containing the names of child actors in creation order
     */
    public Set<String> getNamesOfChildren() {
        return this.NamesOfChildren;
    }

    /**
     * Returns the name of this actor's parent, if any.
     * 
     * @return the parent actor's name, or null if this actor has no parent
     */
    public String getParentName() {
        return this.parentName;
    }

    /**
     * Checks if this actor is still alive and able to process messages.
     * 
     * @return true if the actor is alive, false otherwise
     */
    public boolean isAlive() {
        return running.get() && this.object != null && actorThread != null && actorThread.isAlive();
    }

    
    /**
     * Sets the parent name for this actor.
     * 
     * @param parentName the name of the parent actor
     */
    public void setParentName(String parentName) {
        this.parentName = parentName;
    }


    /**
     * Returns the actor system this actor belongs to.
     * 
     * @return the actor system instance
     */
    public ActorSystem system() {
        return this.actorSystem;
    }

    /**
     * Returns the name of this actor.
     * 
     * @return the actor's unique name
     */
    public String getName() {
        return this.actorName;
    }


    /**
     * Initializes the logger with the specified name.
     * 
     * @param loggerName the name for the logger
     */
    public void initLogger(String loggerName) {
        logger = Logger.getLogger(loggerName);
    }


    /**
     * Sends a message to the actor defined by this reference.
     *
     * The specified action is executed on the actor's object asynchronously in actor's thread context.
     * This method does not wait for completion of the action, it returns immediately.
     * Messages are processed in the order they are received (FIFO).
     *
     * @param action action to be executed on actor's object.
     * @return CompletableFuture that completes when the action finishes execution.
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * // Create an actor
     * ActorRef<Counter> counter = system.actorOf("counter", new Counter());
     *
     * // Send a message to increment the counter
     * CompletableFuture<Void> future = counter.tell(c -> c.increment());
     *
     * // Send multiple messages (processed in order)
     * counter.tell(c -> c.increment());
     * counter.tell(c -> c.increment());
     * counter.tell(c -> System.out.println("Counter value: " + c.getValue()));
     *
     * // Wait for completion if needed
     * future.get(); // Blocks until the action completes
     * }</pre>
     */
    public CompletableFuture<Void> tell(Consumer<T> action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        T target = this.object;

        // Add message to the queue
        messageQueue.offer(() -> {
            try {
                action.accept(target);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Sends a message to the actor for immediate execution, bypassing the normal message queue.
     *
     * The specified action is executed immediately on a new virtual thread, without waiting
     * for previously queued messages to complete. This allows for urgent/priority messages
     * that need to be processed right away.
     *
     * Note: This method executes concurrently with regular tell() messages, so proper
     * synchronization should be considered if the actor's state could be accessed simultaneously.
     *
     * @param action action to be executed on actor's object immediately.
     * @return CompletableFuture that completes when the immediate action finishes.
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * // Create an actor and queue some regular messages
     * ActorRef<Counter> counter = system.actorOf("counter", new Counter());
     * counter.tell(c -> { Thread.sleep(1000); c.increment(); }); // Long running task
     * counter.tell(c -> c.increment()); // Queued after long task
     *
     * // Send urgent message that executes immediately (concurrently)
     * CompletableFuture<Void> urgent = counter.tellNow(c -> {
     *     System.out.println("Emergency: Current value = " + c.getValue());
     * });
     *
     * // The urgent message executes immediately, even while long task is running
     * urgent.get(); // Completes quickly
     *
     * // Use case: Emergency shutdown, logging, monitoring
     * counter.tellNow(c -> logger.warning("Actor overloaded!"));
     * }</pre>
     */
    public CompletableFuture<Void> tellNow(Consumer<T> action) {
        T target = this.object;
        return CompletableFuture.runAsync(() -> action.accept(target),
                                        Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Sends a message to actor and returns a CompletableFuture to be completed with the response value.
     *
     * Performs the specified call on the actor's object asynchronously. The call is executed in this actor's
     * thread context, and the future is completed with the result value. Messages are processed in order (FIFO),
     * so this ask() will wait for previously sent tell() messages to complete first.
     *
     * This method returns a CompletableFuture, which is completed with a result once the actor's call completes.
     * If an exception occurs during the actor's call, the exception is passed to the CompletableFuture.
     *
     * @param <R> actor call response class
     * @param action action to be executed on actor's object, return value will be the response
     * @return CompletableFuture to be completed with the actor's call result
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * // Create an actor
     * ActorRef<Counter> counter = system.actorOf("counter", new Counter());
     *
     * // Send some tell messages first
     * counter.tell(c -> c.increment());
     * counter.tell(c -> c.increment());
     *
     * // Ask for the current value (waits for above messages to complete first)
     * CompletableFuture<Integer> valueFuture = counter.ask(c -> c.getValue());
     * Integer currentValue = valueFuture.get(); // Should be 2
     *
     * // Ask for computed result
     * CompletableFuture<String> statusFuture = counter.ask(c -> {
     *     return "Counter value is: " + c.getValue();
     * });
     * String status = statusFuture.get();
     *
     * // Chain multiple operations
     * counter.ask(c -> c.getValue())
     *        .thenAccept(value -> System.out.println("Current: " + value));
     * }</pre>
     */
    public <R> CompletableFuture<R> ask(Function<T, R> action) {
        CompletableFuture<R> future = new CompletableFuture<>();
        T target = this.object;

        // Add message to the queue
        messageQueue.offer(() -> {
            try {
                R result = action.apply(target);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Sends a message to the actor and returns a response, bypassing the mailbox and executing immediately.
     * Unlike ask(), which queues messages in the actor's message queue,
     * askNow() executes the function immediately on a separate virtual thread, allowing it to
     * run concurrently with queued messages.
     *
     * This method is useful when:
     * - You need to query the actor's state immediately without waiting for queued messages
     * - Concurrent execution with queued messages is desired
     *
     * @param <R> the type of the response
     * @param action the function to execute on this actor's object
     * @return a CompletableFuture that completes with the function's result
     */
    public <R> CompletableFuture<R> askNow(Function<T, R> action) {
        T target = this.object;
        return CompletableFuture.supplyAsync(() -> action.apply(target),
                                            Executors.newVirtualThreadPerTaskExecutor());
    }


    /**
     * Sends a message to this actor using a specific executor service.
     * The action is executed asynchronously on the provided executor service,
     * and the result is processed in this actor's thread context.
     *
     * @param action the action to execute on this actor's object
     * @param ws the executor service to use for executing the action
     * @return a CompletableFuture that completes when the action is processed
     */
    public CompletableFuture<Void> tell(Consumer<T> action, ExecutorService ws) {
        T target = this.object;

        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> action.accept(target), ws);
        return CompletableFuture.runAsync(() -> {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.log(Level.WARNING, "Exception occurred while waiting for the result of the tell method.", e);
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Sends a message to this actor and returns a response using a specific executor service.
     * The action is executed asynchronously on the provided executor service,
     * and the result is processed in this actor's thread context.
     *
     * @param <R> the type of the response
     * @param action the function to execute on this actor's object
     * @param ws the executor service to use for executing the action
     * @return a CompletableFuture containing the response from the action
     */
    public <R> CompletableFuture<R> ask(Function<T, R> action, ExecutorService ws) {
        T target = this.object;

        CompletableFuture<R> task = CompletableFuture.supplyAsync(() -> {
            return action.apply(target);
        }, ws);

        return CompletableFuture.supplyAsync(() -> {
            R result = null;
            try {
                result = task.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.log(Level.WARNING, "Exception occurred while waiting for the result of the ask method.", e);
            }
            return result;
        }, Executors.newVirtualThreadPerTaskExecutor());
    }
    

    // ========================================================================
    // JSON State API
    // ========================================================================

    /**
     * Returns the JSON state container for this actor, creating it if necessary.
     *
     * <p>The JSON state provides a dynamic, XPath-style accessor for storing
     * workflow state that doesn't need compile-time type safety. This is useful
     * for temporary state, debug information, and YAML workflow integration.</p>
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * ActorRef<MyActor> actor = system.actorOf("worker", new MyActor());
     *
     * // Store values using XPath-style paths
     * actor.json().put("workflow.retry", 3);
     * actor.json().put("hosts[0]", "server1.example.com");
     *
     * // Read values
     * int retry = actor.json().getInt("$.workflow.retry", 0);
     * String host = actor.json().getString("$.hosts[0]");
     * }</pre>
     *
     * @return the JSON state container (never null)
     * @since 2.10.0
     */
    public JsonState json() {
        if (jsonState == null) {
            synchronized (this) {
                if (jsonState == null) {
                    jsonState = new JsonState();
                }
            }
        }
        return jsonState;
    }

    /**
     * Checks if this actor has any JSON state.
     *
     * @return true if JSON state exists and is not empty
     * @since 2.10.0
     */
    public boolean hasJsonState() {
        return jsonState != null && !jsonState.isEmpty();
    }

    /**
     * Convenience method to put a value into the JSON state.
     *
     * @param path the XPath-style path (e.g., "workflow.retry" or "$.hosts[0]")
     * @param value the value to store
     * @return this ActorRef for method chaining
     * @since 2.10.0
     */
    public ActorRef<T> putJson(String path, Object value) {
        json().put(path, value);
        return this;
    }

    /**
     * Convenience method to get a string from the JSON state.
     *
     * @param path the XPath-style path
     * @return the string value, or null if not found
     * @since 2.10.0
     */
    public String getJsonString(String path) {
        return jsonState != null ? jsonState.getString(path) : null;
    }

    /**
     * Convenience method to get a string from the JSON state with default.
     *
     * @param path the XPath-style path
     * @param defaultValue the default value if not found
     * @return the string value, or defaultValue if not found
     * @since 2.10.0
     */
    public String getJsonString(String path, String defaultValue) {
        return jsonState != null ? jsonState.getString(path, defaultValue) : defaultValue;
    }

    /**
     * Convenience method to get an integer from the JSON state.
     *
     * @param path the XPath-style path
     * @param defaultValue the default value if not found
     * @return the integer value, or defaultValue if not found
     * @since 2.10.0
     */
    public int getJsonInt(String path, int defaultValue) {
        return jsonState != null ? jsonState.getInt(path, defaultValue) : defaultValue;
    }

    /**
     * Convenience method to get a boolean from the JSON state.
     *
     * @param path the XPath-style path
     * @param defaultValue the default value if not found
     * @return the boolean value, or defaultValue if not found
     * @since 2.10.0
     */
    public boolean getJsonBoolean(String path, boolean defaultValue) {
        return jsonState != null ? jsonState.getBoolean(path, defaultValue) : defaultValue;
    }

    /**
     * Convenience method to check if a path exists in the JSON state.
     *
     * @param path the XPath-style path
     * @return true if the path exists and has a non-null value
     * @since 2.10.0
     */
    public boolean hasJson(String path) {
        return jsonState != null && jsonState.has(path);
    }

    /**
     * Clears all JSON state for this actor.
     *
     * @since 2.10.0
     */
    public void clearJsonState() {
        if (jsonState != null) {
            jsonState.clear();
        }
    }

    /**
     * Returns a pretty-printed JSON string for the subtree at the given path.
     *
     * <p>If path is null or empty, returns the entire JSON state.
     * If the path doesn't exist, returns "null".
     * If JSON state has not been initialized, returns "{}".</p>
     *
     * @param path the XPath-style path (e.g., "namespaces", "cluster.nodes")
     * @return formatted JSON string for the subtree
     * @since 2.15.0
     */
    public String toStringOfJson(String path) {
        if (jsonState == null) {
            return "{}";
        }
        return jsonState.toStringOfJson(path);
    }

    /**
     * Returns a YAML string for the subtree at the given path.
     *
     * <p>If path is null or empty, returns the entire JSON state as YAML.
     * If the path doesn't exist, returns "null".
     * If JSON state has not been initialized, returns "{}".</p>
     *
     * @param path the XPath-style path (e.g., "namespaces", "cluster.nodes")
     * @return YAML string for the subtree
     * @since 2.15.0
     */
    public String toStringOfYaml(String path) {
        if (jsonState == null) {
            return "{}";
        }
        return jsonState.toStringOfYaml(path);
    }

    /**
     * Sets the last action result for this actor.
     *
     * <p>The result is stored in the JSON state under the {@code _lastResult} key,
     * unifying result storage with the JSON State API.</p>
     *
     * @param result the result to store
     * @since 2.13.0
     * @since 2.14.0 Now stores in JSON state instead of separate field
     */
    public void setLastResult(ActionResult result) {
        if (result != null && result.getResult() != null) {
            json().put(LAST_RESULT_KEY, result.getResult());
        } else {
            json().put(LAST_RESULT_KEY, null);
        }
    }

    /**
     * Gets the last action result for this actor.
     *
     * <p>Retrieves the result from the JSON state where it is stored under {@code _lastResult}.</p>
     *
     * @return the last result wrapped in ActionResult, or null if no action has been executed
     * @since 2.13.0
     * @since 2.14.0 Now retrieves from JSON state instead of separate field
     */
    public ActionResult getLastResult() {
        String resultValue = json().getString(LAST_RESULT_KEY);
        if (resultValue != null) {
            return new ActionResult(true, resultValue);
        }
        return null;
    }

    /**
     * Expands variable references in a string.
     *
     * <p>Replaces {@code ${varName}} patterns with values from this actor's JSON state:</p>
     * <ul>
     *   <li>{@code ${result}} - the result of the last action (stored in JSON state as {@code result})</li>
     *   <li>{@code ${key}} or {@code ${nested.key}} - values from this actor's JSON state</li>
     *   <li>{@code ${json.key}} - also looks up in JSON state (with optional "json." prefix)</li>
     * </ul>
     *
     * <p>If a variable is not found, the pattern is left unchanged.</p>
     *
     * @param input the string containing ${...} patterns
     * @return the expanded string
     * @since 2.13.0
     * @since 2.14.0 All variables (including result) stored in unified JSON state
     */
    public String expandVariables(String input) {
        if (input == null || !input.contains("${")) {
            return input;
        }

        String expanded = input;

        // All variables are stored in jsonState
        if (jsonState != null) {
            int startIndex = 0;
            while (true) {
                int start = expanded.indexOf("${", startIndex);
                if (start == -1) break;

                int end = expanded.indexOf("}", start);
                if (end == -1) break;

                String varName = expanded.substring(start + 2, end);

                // Strip optional "json." prefix
                String jsonPath = varName.startsWith("json.") ? varName.substring(5) : varName;

                String value = getVariableValue(jsonPath);
                if (value != null) {
                    expanded = expanded.substring(0, start) + value + expanded.substring(end + 1);
                    // Don't advance startIndex since we replaced content
                    continue;
                }
                startIndex = end + 1;
            }
        }

        return expanded;
    }

    /**
     * Gets a variable value as a string, handling objects and arrays.
     *
     * <p>For scalar values (string, number, boolean), returns the text representation.
     * For objects and arrays, returns the JSON string representation.</p>
     *
     * @param path the path to the value
     * @return the string representation, or null if not found
     */
    private String getVariableValue(String path) {
        if (jsonState == null) {
            return null;
        }
        var node = jsonState.select(path);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        // For objects and arrays, return JSON string representation
        if (node.isObject() || node.isArray()) {
            return node.toString();
        }
        // For scalar values, return text representation
        return node.asText();
    }

    // ========================================================================
    // Lifecycle Management
    // ========================================================================

    /**
     * Destroys the actor and cleans up its resources.
     * Pending messages will be cancelled and the actor will be removed from its system.
     * This method implements the AutoCloseable interface for resource management.
     */
    @Override
    public void close() {
        // Stop the message processing loop
        running.set(false);

        // Interrupt the actor thread to wake it up from blocking queue operations
        if (actorThread != null) {
            actorThread.interrupt();
            try {
                actorThread.join(5000); // Wait up to 5 seconds for the thread to finish
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "InterruptedException occurred while waiting for actor thread to terminate.", e);
                Thread.currentThread().interrupt();
            }
        }

        // Clear any remaining messages
        messageQueue.clear();

        this.object = null;

        if (this.system() != null && system().hasActor(this.actorName)) {
            system().removeActor(this.actorName);
        }
    }

    /**
     * Starts the message processing loop in a virtual thread.
     *
     * @return the started thread
     */
    private Thread startMessageLoop() {
        Thread thread = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    Runnable message = messageQueue.take(); // Block until message arrives
                    message.run();
                } catch (InterruptedException e) {
                    // Thread was interrupted, likely shutting down
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Exception occurred while processing message in actor: " + actorName, e);
                }
            }
        });
        return thread;
    }

    /**
     * Clears all pending messages from this actor's message queue.
     *
     * This method removes all messages that are currently waiting to be processed
     * by this actor, without affecting other actors in the system.
     * The currently running message will complete normally.
     * Only affects messages sent via tell() and ask() - tellNow() messages are not queued.
     *
     * @return the number of messages that were cleared
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * // Create an actor and send multiple messages
     * ActorRef<Counter> counter = system.actorOf("counter", new Counter());
     *
     * // Queue several messages
     * counter.tell(c -> { Thread.sleep(1000); c.increment(); }); // Long running (starts immediately)
     * counter.tell(c -> c.increment()); // Queued
     * counter.tell(c -> c.increment()); // Queued
     * counter.ask(c -> c.getValue());   // Queued
     *
     * // Clear all pending messages
     * int cleared = counter.clearPendingMessages();
     * System.out.println("Cleared " + cleared + " messages");
     *
     * // Use cases:
     * // 1. Cancel batch operations
     * // 2. Reset actor state handling
     * // 3. Emergency stop of pending work
     * // 4. Load balancing (redirect work to other actors)
     *
     * // tellNow() is not affected by clearing
     * counter.tellNow(c -> System.out.println("This executes immediately"));
     * }</pre>
     */
    public int clearPendingMessages() {
        int clearedFromQueue = messageQueue.size();
        messageQueue.clear();

        logger.log(Level.INFO, "Cleared " + clearedFromQueue + " pending messages from actor: " + actorName);
        return clearedFromQueue;
    }

}
