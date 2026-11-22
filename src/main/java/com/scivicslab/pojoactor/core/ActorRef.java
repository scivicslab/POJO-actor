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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
 * <p>Whatever an action throws -- an {@code Exception} or an {@code Error} -- completes the
 * future returned by {@code tell()} or {@code ask()} exceptionally, so that a caller joining it
 * never waits forever. After an ordinary {@code Error} such as {@code StackOverflowError} the
 * actor goes on with its next message. A {@link VirtualMachineError} other than
 * {@code StackOverflowError} (for example {@code OutOfMemoryError}) is rethrown after the
 * future is completed, and ends the actor's message loop; the messages still queued then
 * complete exceptionally too. An action that leaves the thread's interrupt flag set does not
 * stop the actor: only {@link #close()} ends the loop.</p>
 *
 * <p>A message thrown away before it runs still completes its future: cancelled when discarded on
 * request ({@link #clearPendingMessages()}, {@link WorkerPool#cancelJobsForActor(String)}), failed
 * with {@link ActorClosedException} when its actor or system closes, or when it is sent to an
 * actor that is already closed.</p>
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
    private final BlockingQueue<Message> messageQueue = new LinkedBlockingQueue<>();

    /**
     * A queued message: the code to run on the actor's thread, and the future its caller holds.
     * The future is kept beside the code so that a message the loop will never run can still be
     * completed (see {@link #failPendingMessages}).
     */
    private record Message(Runnable body, CompletableFuture<?> future) {
    }
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread actorThread;

    /**
     * Work this actor runs off its mailbox loop: a job given to {@code tell(action, ws)} or
     * {@code ask(action, ws)}, or {@code tellNow}/{@code askNow} work. Until it starts, close()
     * may claim it and fail its future. Once the job has claimed itself it is running, and
     * close() waits for it to end.
     */
    private static final class OffLoopJob {
        private final AtomicBoolean claimed = new AtomicBoolean();
        /** The caller's future, which close() fails if it claims the job; null when close() need not. */
        private final CompletableFuture<?> future;
        private final CountDownLatch ended = new CountDownLatch(1);
        private volatile Thread runner;

        OffLoopJob(CompletableFuture<?> future) {
            this.future = future;
        }
    }

    /** Off-loop jobs handed to an executor and not yet started; close() fails their futures. */
    private final Set<OffLoopJob> unstartedJobs = ConcurrentHashMap.newKeySet();

    /** Off-loop jobs that have started and not yet ended; close() waits for them. */
    private final Set<OffLoopJob> runningJobs = ConcurrentHashMap.newKeySet();

    /** How long close() waits for the message or off-loop jobs that are running. */
    private static final long CLOSE_WAIT_MILLIS = 5000;

    // CopyOnWriteArraySet preserves insertion order (unlike ConcurrentSkipListSet which sorts)
    CopyOnWriteArraySet<String> NamesOfChildren = new CopyOnWriteArraySet<>();
    String parentName;

    // === JSON State (lazy-initialized) ===
    private volatile JsonState jsonState;

    // === AttributeKey store (lazy-initialized) ===
    private volatile Map<AttributeKey<?>, Object> attributes;

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
        if (!running.get()) {
            return closedFuture();
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        T target = this.object;

        // Add message to the queue
        messageQueue.offer(new Message(() -> {
            try {
                action.accept(target);
                future.complete(null);
            } catch (Throwable t) {
                completeExceptionally(future, t);
            }
        }, future));
        failQueuedIfClosed();

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
        if (!running.get()) {
            return closedFuture();
        }
        T target = this.object;
        OffLoopJob job = new OffLoopJob(null);
        return CompletableFuture.runAsync(() -> {
            if (!startJob(job)) throw new ActorClosedException(actorName);
            try { action.accept(target); }
            finally { endJob(job); }
        }, nowExecutor());
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
        if (!running.get()) {
            return closedFuture();
        }
        CompletableFuture<R> future = new CompletableFuture<>();
        T target = this.object;

        // Add message to the queue
        messageQueue.offer(new Message(() -> {
            try {
                R result = action.apply(target);
                future.complete(result);
            } catch (Throwable t) {
                completeExceptionally(future, t);
            }
        }, future));
        failQueuedIfClosed();

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
        if (!running.get()) {
            return closedFuture();
        }
        T target = this.object;
        OffLoopJob job = new OffLoopJob(null);
        return CompletableFuture.supplyAsync(() -> {
            if (!startJob(job)) throw new ActorClosedException(actorName);
            try { return action.apply(target); }
            finally { endJob(job); }
        }, nowExecutor());
    }

    /**
     * The executor that runs {@link #tellNow} and {@link #askNow} work: a new virtual thread per
     * call. Package-private so that a test in this package can hold that work before it starts.
     *
     * @return the executor for work that bypasses the mailbox
     */
    java.util.concurrent.Executor nowExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }


    /**
     * Sends a message to this actor using a specific executor service.
     * The action is executed asynchronously on the provided executor service,
     * and the result is processed in this actor's thread context.
     *
     * <p>When {@code ws} is a {@link ManagedThreadPool} and the caller is already on one of its
     * real threads ({@link ManagedThreadPool#ownsCurrentThread()}), the action runs right there and
     * the returned future is already complete: a task on the pool that gave the pool more work and
     * waited for it could otherwise deadlock the pool. A {@code Throwable} from the action completes
     * the future exceptionally, under the same rules as on the pool.</p>
     *
     * @param action the action to execute on this actor's object
     * @param ws the executor service to use for executing the action
     * @return a CompletableFuture that completes when the action is processed
     */
    public CompletableFuture<Void> tell(Consumer<T> action, ExecutorService ws) {
        if (!running.get()) {
            return closedFuture();
        }
        T target = this.object;

        CompletableFuture<Void> future = new CompletableFuture<>();
        // This path never goes through the actor's virtual-thread message loop: the action runs on
        // a real thread of ws (or, below, on the caller's real thread), not in the actor's queue,
        // so running it inline does not change the order of the actor's queued messages.
        if (ws instanceof ManagedThreadPool pool && pool.ownsCurrentThread()) {
            // Already on one of this pool's real threads: run here. Submitting and waiting could
            // deadlock the pool when all its real threads wait the same way.
            runJob(new OffLoopJob(future), () -> { action.accept(target); future.complete(null); });
        } else if (ws instanceof ManagedThreadPool pool) {
            OffLoopJob job = trackJob(future);
            handOverRun(future, () -> pool.submitForActor(actorName,
                    () -> runJob(job, () -> { action.accept(target); future.complete(null); }), future));
        } else {
            OffLoopJob job = trackJob(future);
            CompletableFuture<Void> task = handOver(future, () -> CompletableFuture.runAsync(() -> {
                if (!startJob(job)) throw new ActorClosedException(actorName);
                try { action.accept(target); }
                finally { endJob(job); }
            }, ws));
            CompletableFuture.runAsync(() -> {
                try { task.get(); future.complete(null); }
                catch (ExecutionException e) {
                    if (failIfClosedCause(future, e)) return;
                    logger.log(Level.WARNING, "Exception occurred while waiting for the result of the tell method.", e);
                    future.completeExceptionally(e);
                }
                catch (InterruptedException e) {
                    logger.log(Level.WARNING, "Exception occurred while waiting for the result of the tell method.", e);
                    future.completeExceptionally(e);
                }
            }, Executors.newVirtualThreadPerTaskExecutor());
        }
        return future;
    }

    /**
     * Sends a message to this actor and returns a response using a specific executor service.
     * The action is executed asynchronously on the provided executor service,
     * and the result is processed in this actor's thread context.
     *
     * <p>When {@code ws} is a {@link ManagedThreadPool} and the caller is already on one of its
     * real threads ({@link ManagedThreadPool#ownsCurrentThread()}), the action runs right there and
     * the returned future is already complete: a task on the pool that gave the pool more work and
     * waited for it could otherwise deadlock the pool. A {@code Throwable} from the action completes
     * the future exceptionally, under the same rules as on the pool.</p>
     *
     * @param <R> the type of the response
     * @param action the function to execute on this actor's object
     * @param ws the executor service to use for executing the action
     * @return a CompletableFuture containing the response from the action
     */
    public <R> CompletableFuture<R> ask(Function<T, R> action, ExecutorService ws) {
        if (!running.get()) {
            return closedFuture();
        }
        T target = this.object;

        CompletableFuture<R> future = new CompletableFuture<>();
        // This path never goes through the actor's virtual-thread message loop: the action runs on
        // a real thread of ws (or, below, on the caller's real thread), not in the actor's queue,
        // so running it inline does not change the order of the actor's queued messages.
        if (ws instanceof ManagedThreadPool pool && pool.ownsCurrentThread()) {
            // Already on one of this pool's real threads: run here. Submitting and waiting could
            // deadlock the pool when all its real threads wait the same way.
            runJob(new OffLoopJob(future), () -> future.complete(action.apply(target)));
        } else if (ws instanceof ManagedThreadPool pool) {
            OffLoopJob job = trackJob(future);
            handOverRun(future, () -> pool.submitForActor(actorName,
                    () -> runJob(job, () -> future.complete(action.apply(target))), future));
        } else {
            OffLoopJob job = trackJob(future);
            CompletableFuture<R> task = handOver(future, () -> CompletableFuture.supplyAsync(() -> {
                if (!startJob(job)) throw new ActorClosedException(actorName);
                try { return action.apply(target); }
                finally { endJob(job); }
            }, ws));
            CompletableFuture.supplyAsync(() -> {
                try { future.complete(task.get()); }
                catch (ExecutionException e) {
                    if (failIfClosedCause(future, e)) return null;
                    logger.log(Level.WARNING, "Exception occurred while waiting for the result of the ask method.", e);
                    future.completeExceptionally(e);
                }
                catch (InterruptedException e) {
                    logger.log(Level.WARNING, "Exception occurred while waiting for the result of the ask method.", e);
                    future.completeExceptionally(e);
                }
                return null;
            }, Executors.newVirtualThreadPerTaskExecutor());
        }
        return future;
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
     * <p>Stored in the JSON state under the {@code _lastResult} key. Turing Workflow's
     * JEXL context exposes it as {@code result}. The value is a plain string: what an
     * action returned is Turing-workflow's vocabulary, not this class's.</p>
     *
     * @param value the value to store, or null to clear it
     * @since 2.13.0
     * @since 2.14.0 Now stores in JSON state instead of separate field
     */
    public void setLastResultValue(String value) {
        // A TextNode, so that a result that looks like JSON is not parsed into a structure
        // (which getString would then read back as an empty string).
        json().put(LAST_RESULT_KEY, value == null ? null : com.fasterxml.jackson.databind.node.TextNode.valueOf(value));
    }

    /**
     * Gets the last action result for this actor.
     *
     * <p>Retrieves the result from the JSON state where it is stored under {@code _lastResult}.</p>
     *
     * @return the stored value, or null if nothing has been stored
     * @since 2.13.0
     * @since 2.14.0 Now retrieves from JSON state instead of separate field
     */
    public String getLastResultValue() {
        return json().getString(LAST_RESULT_KEY);
    }


    // ========================================================================
    // AttributeKey API
    // ========================================================================

    /**
     * Stores a typed attribute value. The map is lazy-initialized on first use.
     *
     * @throws IllegalArgumentException if value is not an instance of key's declared type
     */
    public <V> void putAttribute(AttributeKey<V> key, V value) {
        if (!key.type().isInstance(value)) {
            throw new IllegalArgumentException(
                "Invalid value for key '" + key.name() + "': expected "
                + key.type().getName() + " but got " + value.getClass().getName());
        }
        if (attributes == null) {
            synchronized (this) {
                if (attributes == null) {
                    attributes = new ConcurrentHashMap<>();
                }
            }
        }
        attributes.put(key, value);
    }

    /**
     * Returns the attribute value for the given key, or null if not set.
     */
    @SuppressWarnings("unchecked")
    public <V> V getAttribute(AttributeKey<V> key) {
        if (attributes == null) return null;
        return (V) attributes.get(key);
    }

    /**
     * Returns the attribute value for the given key, or defaultValue if not set.
     */
    public <V> V getAttribute(AttributeKey<V> key, V defaultValue) {
        V value = getAttribute(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns true if an attribute has been set for the given key.
     */
    public boolean hasAttribute(AttributeKey<?> key) {
        return attributes != null && attributes.containsKey(key);
    }

    /**
     * Removes and returns the attribute value for the given key, or null if not set.
     */
    @SuppressWarnings("unchecked")
    public <V> V removeAttribute(AttributeKey<V> key) {
        if (attributes == null) return null;
        return (V) attributes.remove(key);
    }

    // ========================================================================
    // Lifecycle Management
    // ========================================================================

    /**
     * Destroys the actor and cleans up its resources.
     *
     * <p>Stops the message loop (a message already running is interrupted and waited for, up to
     * five seconds; when close() is called from that message itself, it is neither interrupted
     * nor waited for, and the loop ends when the message returns), then fails the future of every
     * message still queued with
     * {@link ActorClosedException}, and removes the actor's registrations from its system. A
     * {@code tell}, {@code ask}, {@code tellNow} or {@code askNow} sent afterwards queues nothing
     * and returns a future already failed with {@code ActorClosedException}.</p>
     *
     * <p>Work this actor handed out off its message loop does not run against the closed actor
     * either. The future of every job given to {@code tell(action, ws)} or {@code ask(action, ws)}
     * that has not started fails at once with {@code ActorClosedException}; the job itself stays
     * in the executor's queue and, when a thread takes it, ends without calling the actor.
     * {@code tellNow} and {@code askNow} work checks just before it calls the actor whether the
     * actor is still running; if it is not, its future fails with {@code ActorClosedException}
     * instead. A job that has already started is not interrupted: it runs to the end on its real
     * or virtual thread, its future completes as usual, and close() waits for it within the same
     * five seconds, so no action of this actor starts after close() has returned. A job running
     * on the thread that calls close() is not waited for.</p>
     *
     * <p>This method implements the AutoCloseable interface for resource management.</p>
     */
    @Override
    public void close() {
        stop();
        awaitStopped();
    }

    /**
     * The first half of {@link #close()}: marks the actor closed, fails the futures of its
     * off-loop jobs that have not started, and interrupts its mailbox loop. Once this has
     * returned, no message or off-loop job of this actor starts.
     *
     * <p>{@link ActorSystem#terminate()} stops every actor before it waits for any, so that while
     * it waits for one actor's running work, another actor's queued work cannot start.</p>
     */
    void stop() {
        // Stop the message processing loop
        running.set(false);

        // Fail this actor's off-loop jobs that have not started. Each stays in its executor's
        // queue and, when a thread takes it, ends at once without calling the actor.
        failUnstartedJobs();

        // Interrupt the actor thread to wake it up from blocking queue operations
        if (actorThread != null && !onOwnLoop()) {
            actorThread.interrupt();
        }
    }

    /**
     * The second half of {@link #close()}: waits, up to five seconds in all, for the off-loop jobs
     * that have started and for the mailbox loop to end, then fails the messages still queued,
     * drops the object and removes the actor's registrations.
     */
    void awaitStopped() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS);
        try {
            // Off-loop jobs that have started run to the end; they are not interrupted.
            awaitRunningJobs(deadline);
            if (actorThread != null && !onOwnLoop()) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                actorThread.join(Math.max(1, left));
            }
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, "InterruptedException occurred while waiting for actor thread to terminate.", e);
            Thread.currentThread().interrupt();
        }

        // Fail the messages that will never run
        failQueued();

        this.object = null;

        // Remove the registrations that point to this actor, under whatever names they are --
        // an alias given to ActorSystem.addActor(actor, alias) included -- and no other actor's,
        // even one registered under this actor's own name.
        if (this.system() != null) {
            system().unregister(this);
        }
    }

    /**
     * Whether the caller is an action on this actor's own mailbox virtual thread. close() then
     * neither interrupts that thread, which would leave the interrupt flag set for the rest of the
     * action, nor joins it, which would wait for itself; the loop ends by itself when the action
     * returns, since running is false.
     */
    private boolean onOwnLoop() {
        return Thread.currentThread() == actorThread;
    }

    /**
     * Starts the message processing loop in a virtual thread.
     *
     * @return the started thread
     */
    private Thread startMessageLoop() {
        Thread thread = Thread.ofVirtual().start(() -> {
            Throwable fatal = null;
            try {
                while (running.get()) {
                    Message message;
                    try {
                        message = messageQueue.take(); // Block until message arrives
                    } catch (InterruptedException e) {
                        if (!running.get()) {
                            break; // close() interrupted the wait
                        }
                        // An action left the interrupt flag set, so this take() threw at once.
                        // The throw has cleared the flag; the actor is still running, so go on.
                        continue;
                    }
                    try {
                        message.body().run();
                    } catch (Throwable t) {
                        if (isFatal(t)) {
                            // The JVM itself is failing; do not carry on as if nothing happened.
                            logger.log(Level.SEVERE, t.getClass().getName() + " while processing message in actor: "
                                    + actorName + "; ending its message loop", t);
                            fatal = t;
                            throw (VirtualMachineError) t;
                        }
                        // An ordinary Error (for example StackOverflowError) must not kill the loop:
                        // the next message still has to run.
                        logger.log(Level.WARNING, "Throwable occurred while processing message in actor: " + actorName, t);
                    }
                }
            } finally {
                if (fatal != null) {
                    running.set(false);
                    failPendingMessages(fatal);
                }
            }
        });
        return thread;
    }

    /**
     * Completes the future of every message still queued exceptionally, after the message loop
     * has ended on a fatal error: nothing will run them, and a caller joining one of their futures
     * would otherwise wait forever. Each future completes with an {@link IllegalStateException}
     * whose cause is the error that ended the loop.
     *
     * <p>Not called when {@link #close()} ends the loop: close() fails the messages it discards
     * with {@link ActorClosedException} instead.</p>
     *
     * @param cause the error that ended the message loop
     */
    /** A future already failed with {@link ActorClosedException}, for a message sent after close(). */
    private <R> CompletableFuture<R> closedFuture() {
        return CompletableFuture.failedFuture(new ActorClosedException(actorName));
    }

    /** Records an off-loop job about to be handed to an executor, until it starts or its future completes. */
    private OffLoopJob trackJob(CompletableFuture<?> future) {
        OffLoopJob job = new OffLoopJob(future);
        unstartedJobs.add(job);
        future.whenComplete((result, failure) -> unstartedJobs.remove(job));
        return job;
    }

    /**
     * Hands a tracked job to its executor. When the executor refuses it, fails the caller's future
     * (so the job is no longer tracked) and rethrows. When the actor was closed between the
     * caller's check and the hand-over, close() may have failed the unstarted jobs before this one
     * was recorded, so that is done again now rather than leave the job waiting for a thread.
     */
    private <V> V handOver(CompletableFuture<?> future, java.util.function.Supplier<V> submit) {
        V submitted;
        try {
            submitted = submit.get();
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        }
        if (!running.get()) {
            failUnstartedJobs();
        }
        return submitted;
    }

    private void handOverRun(CompletableFuture<?> future, Runnable submit) {
        handOver(future, () -> { submit.run(); return null; });
    }

    /**
     * Called by an off-loop job on the thread that runs it, just before it calls the actor.
     * Claims the job, so that close() no longer fails it, and records it as running, so that
     * close() waits for it. When close() claimed it first, or the actor has been closed, the job
     * must not run; in the second case its future, if it has one, is failed here.
     *
     * <p>The job is recorded as running before {@code running} is read, and close() clears
     * {@code running} before it reads the running jobs: so either the job sees the actor closed,
     * or close() sees the job and waits for it.</p>
     *
     * @return true when the job may call the actor; {@link #endJob} must follow
     */
    private boolean startJob(OffLoopJob job) {
        if (!job.claimed.compareAndSet(false, true)) {
            return false;
        }
        unstartedJobs.remove(job);
        job.runner = Thread.currentThread();
        runningJobs.add(job);
        if (!running.get()) {
            endJob(job);
            if (job.future != null) {
                job.future.completeExceptionally(new ActorClosedException(actorName));
            }
            return false;
        }
        return true;
    }

    /** Called when a running off-loop job has ended. */
    private void endJob(OffLoopJob job) {
        runningJobs.remove(job);
        job.ended.countDown();
    }

    /**
     * Runs an off-loop job on the current thread: the body completes the job's future; whatever it
     * throws completes the future exceptionally.
     */
    private void runJob(OffLoopJob job, Runnable body) {
        if (!startJob(job)) {
            return;
        }
        try {
            body.run();
        } catch (Throwable t) {
            completeExceptionally(job.future, t);
        } finally {
            endJob(job);
        }
    }

    /** Fails, with {@link ActorClosedException}, the future of every off-loop job not yet started. */
    private void failUnstartedJobs() {
        for (OffLoopJob job : unstartedJobs) {
            if (job.claimed.compareAndSet(false, true)) {
                job.future.completeExceptionally(new ActorClosedException(actorName));
            }
        }
    }

    /**
     * Waits, until {@code deadline}, for the off-loop jobs that are running to end. A job running
     * on the calling thread -- close() called from inside one of this actor's own off-loop
     * actions -- is not waited for: it would wait for itself.
     */
    private void awaitRunningJobs(long deadline) throws InterruptedException {
        Thread self = Thread.currentThread();
        for (OffLoopJob job : runningJobs) {
            if (job.runner == self) {
                continue;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0 || !job.ended.await(left, TimeUnit.NANOSECONDS)) {
                logger.log(Level.WARNING, "close() of actor " + actorName
                        + " stopped waiting for an off-loop job that is still running");
                return;
            }
        }
    }

    /**
     * When a job on another executor failed because the actor was closed before it ran, fails
     * {@code future} with that {@link ActorClosedException} itself rather than wrapped.
     *
     * @return true when {@code e} was caused by the actor being closed
     */
    private static boolean failIfClosedCause(CompletableFuture<?> future, ExecutionException e) {
        if (e.getCause() instanceof ActorClosedException closed) {
            future.completeExceptionally(closed);
            return true;
        }
        return false;
    }

    /** Fails every message still queued with {@link ActorClosedException}: the actor is closed. */
    private void failQueued() {
        Message message;
        while ((message = messageQueue.poll()) != null) {
            message.future().completeExceptionally(new ActorClosedException(actorName));
        }
    }

    /**
     * Called after a message is queued. If the actor was closed between the caller's check and
     * the queueing, nothing will take the message, so fail it (and anything else left) now.
     */
    private void failQueuedIfClosed() {
        if (!running.get()) {
            failQueued();
        }
    }

    private void failPendingMessages(Throwable cause) {
        Message message;
        while ((message = messageQueue.poll()) != null) {
            message.future().completeExceptionally(new IllegalStateException(
                    "the message loop of actor " + actorName + " ended before this message ran", cause));
        }
    }

    /**
     * Completes {@code future} exceptionally with {@code t}, whatever kind of {@link Throwable}
     * it is, so that a caller waiting on the future is never left waiting for a message whose
     * action failed.
     *
     * <p>A fatal error (see {@link #isFatal}) is rethrown after the future is completed: the JVM
     * is failing, and the thread that ran the action should not continue as if nothing happened.
     * On this actor's own message loop that ends the loop. Any other {@code Error}, such as
     * {@link StackOverflowError}, only completes the future.</p>
     *
     * @param future the future of the message whose action failed
     * @param t      what the action threw
     */
    private static void completeExceptionally(CompletableFuture<?> future, Throwable t) {
        future.completeExceptionally(t);
        if (isFatal(t)) {
            throw (VirtualMachineError) t;
        }
    }

    /**
     * Whether {@code t} means the JVM can no longer be trusted to run the next message: a
     * {@link VirtualMachineError} such as {@link OutOfMemoryError} or {@link InternalError}.
     *
     * <p>{@link StackOverflowError} is also a {@code VirtualMachineError}, but it is not fatal:
     * by the time it is caught here the deep stack that caused it has unwound, so the thread can
     * go on. A long input that recursed too deeply, for example a regular expression run on a
     * very long line, must fail one message, not stop the actor.</p>
     *
     * @param t what an action threw
     * @return true when {@code t} is to be rethrown after its future is completed
     */
    private static boolean isFatal(Throwable t) {
        return t instanceof VirtualMachineError && !(t instanceof StackOverflowError);
    }

    /**
     * Clears all pending messages from this actor's message queue.
     *
     * This method removes all messages that are currently waiting to be processed
     * by this actor, without affecting other actors in the system.
     * The currently running message will complete normally.
     * Only affects messages sent via tell() and ask() - tellNow() messages are not queued.
     * The future of each cleared message, and of each cancelled pool job, is cancelled, so its
     * caller sees a {@code CancellationException}.
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
        int clearedFromQueue = 0;
        Message message;
        while ((message = messageQueue.poll()) != null) {
            message.future().cancel(false);
            clearedFromQueue++;
        }

        int clearedFromPool = 0;
        if (actorSystem != null) {
            ExecutorService pool = actorSystem.getManagedThreadPool();
            if (pool instanceof ManagedThreadPool mtp) {
                clearedFromPool = mtp.cancelJobsForActor(actorName);
            }
        }

        logger.log(Level.INFO, "Cleared " + clearedFromQueue + " messages and " + clearedFromPool + " pool jobs from actor: " + actorName);
        return clearedFromQueue + clearedFromPool;
    }

}
