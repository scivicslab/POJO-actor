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
     * {@code tellNow}/{@code askNow} work that has started and not yet ended, each on its own
     * virtual thread outside the mailbox. close() waits for it.
     */
    private final Set<NowWork> runningNowWork = ConcurrentHashMap.newKeySet();

    /** One piece of tellNow/askNow work while it runs: the virtual thread running it, and its end. */
    private static final class NowWork {
        private final Thread runner = Thread.currentThread();
        private final CountDownLatch ended = new CountDownLatch(1);
    }

    /** How long close() waits, in all, for the running message and running tellNow/askNow work. */
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
     * The specified action is executed on the actor's object asynchronously, on the actor's receive
     * loop, which is a virtual thread. This method does not wait for completion of the action, it
     * returns immediately. Messages are processed in the order they are received (FIFO).
     *
     * <p>Guarantees (the design document is ActorMessageGuarantees_261123_oo01; the test class that
     * fixes them for this method is {@code TellAskGuaranteesTest}):</p>
     * <ul>
     *   <li>messages sent with {@code tell} and {@code ask}, with or without an executor, start and
     *       end in the order the sending calls returned;</li>
     *   <li>at most one thread, real or virtual, is running a method of the actor's object for any
     *       of those messages at any time;</li>
     *   <li>the action runs on the actor's receive loop, a virtual thread, not on a real thread.</li>
     * </ul>
     * <p>{@link #tellNow} and {@link #askNow} skip the mailbox and are the only calls that give
     * none of these guarantees.</p>
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
        return CompletableFuture.runAsync(() -> {
            NowWork work = startNowWork();
            try { action.accept(target); }
            finally { endNowWork(work); }
        }, nowExecutor());
    }

    /**
     * Sends a message to actor and returns a CompletableFuture to be completed with the response value.
     *
     * Performs the specified call on the actor's object asynchronously. The call is executed on this actor's
     * receive loop, which is a virtual thread, and the future is completed with the result value. Messages are
     * processed in order (FIFO), so this ask() will wait for previously sent tell() messages to complete first.
     * The guarantees listed at {@link #tell(Consumer)} hold for it; the test class that fixes them for this
     * method is {@code TellAskGuaranteesTest}.
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
        return CompletableFuture.supplyAsync(() -> {
            NowWork work = startNowWork();
            try { return action.apply(target); }
            finally { endNowWork(work); }
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
     * Called by tellNow/askNow work on its virtual thread, just before it calls the actor. Records
     * the work as running, then checks that the actor is open; close() clears {@code running}
     * before it reads the running work. So either the work sees the actor closed and does not
     * call it, or close() sees the work and waits for it.
     *
     * @return the running work, to be passed to {@link #endNowWork}
     * @throws ActorClosedException when the actor has been closed since the work was sent
     */
    private NowWork startNowWork() {
        NowWork work = new NowWork();
        runningNowWork.add(work);
        if (!running.get()) {
            endNowWork(work);
            throw new ActorClosedException(actorName);
        }
        return work;
    }

    private void endNowWork(NowWork work) {
        runningNowWork.remove(work);
        work.ended.countDown();
    }

    /**
     * Waits, until {@code deadline}, for the tellNow/askNow work that is running to end. Work
     * running on the calling thread -- close() called from inside it -- is not waited for: it
     * would wait for itself.
     */
    private void awaitRunningNowWork(long deadline) throws InterruptedException {
        Thread self = Thread.currentThread();
        for (NowWork work : runningNowWork) {
            if (work.runner == self) {
                continue;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0 || !work.ended.await(left, TimeUnit.NANOSECONDS)) {
                logger.log(Level.WARNING, "close() of actor " + actorName
                        + " stopped waiting for tellNow/askNow work that is still running");
                return;
            }
        }
    }


    /**
     * Sends a message to this actor whose action runs on a thread of the executor service
     * {@code ws}, not on the actor's receive loop.
     *
     * <p>The message is queued in the actor's mailbox like any other message, so it starts after the
     * messages sent before it have ended. The receive loop, on its virtual thread, takes it, gives the
     * action to a thread of {@code ws} (a real thread, when {@code ws} is a {@link ManagedThreadPool}),
     * and waits until the action has ended before it takes the next message. The wait keeps the order:
     * a message sent after this one cannot start while the action runs. The real thread that runs the
     * action is what limits CPU-bound work to the size of the pool; the receive loop's virtual thread
     * only waits and holds no real thread.</p>
     *
     * <p>So this message and every other message sent with {@code tell} or {@code ask}, with or without
     * an executor, run one at a time in the order sent: at most one thread, real or virtual, is running
     * a method of the object at any time. Heavy work given here delays the actor's later messages; to
     * run something beside the mailbox, use {@link #tellNow} or {@link #askNow}. The test class that
     * fixes these guarantees for a {@link ManagedThreadPool} is {@code PoolTellAskGuaranteesTest}.</p>
     *
     * <p>When the caller is a real thread of {@code ws} ({@link ManagedThreadPool#ownsCurrentThread()}),
     * the message is still queued and taken in order. If that real thread then waits on the returned
     * future with {@code get} or {@code join}, the receive loop gives the action back to it and it
     * runs the action, so a pool with one real thread does not wait for itself. The test class that
     * fixes this case is {@code PoolThreadCallerGuaranteesTest}.</p>
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
            try { action.accept(target); future.complete(null); }
            catch (Throwable t) { completeExceptionally(future, t); }
        } else if (ws instanceof ManagedThreadPool pool) {
            pool.submitForActor(actorName, () -> {
                try { action.accept(target); future.complete(null); }
                catch (Throwable t) { completeExceptionally(future, t); }
            }, future);
        } else {
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> action.accept(target), ws);
            CompletableFuture.runAsync(() -> {
                try { task.get(); future.complete(null); }
                catch (InterruptedException | ExecutionException e) {
                    logger.log(Level.WARNING, "Exception occurred while waiting for the result of the tell method.", e);
                    future.completeExceptionally(e);
                }
            }, Executors.newVirtualThreadPerTaskExecutor());
        }
        return future;
    }

    /**
     * Sends a message to this actor whose function runs on a thread of the executor service
     * {@code ws}, not on the actor's receive loop, and returns its result.
     *
     * <p>Everything said for {@link #tell(Consumer, ExecutorService)} holds: the message is queued
     * in the mailbox and taken in the order sent, its function runs on a thread of {@code ws} (a real
     * thread, for a {@link ManagedThreadPool}) while the receive loop's virtual thread waits, and at
     * most one thread, real or virtual, is running a method of the object at any time. The test classes
     * that fix this are {@code PoolTellAskGuaranteesTest} and {@code PoolThreadCallerGuaranteesTest}.</p>
     *
     * <p>Asked from inside one of this actor's own messages, the function runs at once, as a method
     * call would: on the calling thread when that is a real thread of the pool, and otherwise on a
     * thread of {@code ws} while the calling thread waits. Queued behind the running message, it would
     * make that message wait for itself. This case is not part of the order guarantee.</p>
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
            try { future.complete(action.apply(target)); }
            catch (Throwable t) { completeExceptionally(future, t); }
        } else if (ws instanceof ManagedThreadPool pool) {
            pool.submitForActor(actorName, () -> {
                try { future.complete(action.apply(target)); }
                catch (Throwable t) { completeExceptionally(future, t); }
            }, future);
        } else {
            CompletableFuture<R> task = CompletableFuture.supplyAsync(() -> action.apply(target), ws);
            CompletableFuture.supplyAsync(() -> {
                try { future.complete(task.get()); }
                catch (InterruptedException | ExecutionException e) {
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
     * nor waited for, and the loop ends when the message returns), then fails the future of every message still queued with
     * {@link ActorClosedException}, and removes the actor's registrations from its system. A
     * {@code tell}, {@code ask}, {@code tellNow} or {@code askNow} sent afterwards queues nothing
     * and returns a future already failed with {@code ActorClosedException}. Jobs this actor
     * submitted to a {@link ManagedThreadPool} are not touched; {@link #clearPendingMessages()}
     * cancels those.</p>
     *
     * <p>{@code tellNow} and {@code askNow} work, which runs on its own virtual thread outside the
     * mailbox, checks just before it calls the actor whether the actor is still open; if not, its
     * future fails with {@code ActorClosedException}. Such work that has already started is not
     * interrupted: it runs to the end, and close() waits for it within the same five seconds, so
     * no tellNow/askNow action starts after close() has returned. Work running on the thread that
     * calls close() is not waited for.</p>
     *
     * <p>This method implements the AutoCloseable interface for resource management.</p>
     */
    @Override
    public void close() {
        // Stop the message processing loop
        running.set(false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS);

        // Interrupt the actor thread to wake it up from blocking queue operations -- unless close()
        // is called from an action on that very thread, the actor's own mailbox virtual thread.
        // Interrupting it would leave the interrupt flag set for the rest of the action, and
        // joining it would wait for itself. The loop ends by itself when the action returns,
        // since running is now false.
        if (actorThread != null && Thread.currentThread() != actorThread) {
            actorThread.interrupt();
        }
        try {
            // tellNow/askNow work that has started runs to the end; it is not interrupted.
            awaitRunningNowWork(deadline);
            if (actorThread != null && Thread.currentThread() != actorThread) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                actorThread.join(Math.max(1, left)); // up to the same 5 seconds in all
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
