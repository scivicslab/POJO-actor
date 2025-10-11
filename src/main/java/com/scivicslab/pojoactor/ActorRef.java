/*
 * Copyright 2025 devteam@scivics-lab.com
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
package com.scivicslab.pojoactor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
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
 * @author devteam@scivics-lab.com
 * @version 1.0.0
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

    ConcurrentSkipListSet<String> NamesOfChildren = new ConcurrentSkipListSet<>();
    String parentName;


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
     * @return a set containing the names of child actors
     */
    public ConcurrentSkipListSet<String> getNamesOfChildren() {
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

        // If using ControllableWorkStealingPool, register the job with actor name
        if (ws instanceof ControllableWorkStealingPool) {
            ControllableWorkStealingPool pool = (ControllableWorkStealingPool) ws;
            CompletableFuture<Void> future = new CompletableFuture<>();

            pool.submitForActor(this.actorName, () -> {
                try {
                    action.accept(target);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            return future;
        } else {
            // Original implementation for other ExecutorServices
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> action.accept(target), ws);
            return CompletableFuture.runAsync(() -> {
                try {
                    task.get();
                } catch (InterruptedException | ExecutionException e) {
                    logger.log(Level.WARNING, "Exception occurred while waiting for the result of the tell method.", e);
                }
            }, Executors.newVirtualThreadPerTaskExecutor());
        }
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

        // If using ControllableWorkStealingPool, register the job with actor name
        if (ws instanceof ControllableWorkStealingPool) {
            ControllableWorkStealingPool pool = (ControllableWorkStealingPool) ws;
            CompletableFuture<R> future = new CompletableFuture<>();

            pool.submitForActor(this.actorName, () -> {
                try {
                    R result = action.apply(target);
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            return future;
        } else {
            // Original implementation for other ExecutorServices
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
    }
    

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
     * Clears all pending messages from this actor's message queue and WorkStealingPool.
     *
     * This method removes all messages that are currently waiting to be processed
     * by this actor, without affecting other actors in the system.
     * The currently running message will complete normally.
     * Only affects messages sent via tell() and ask() - tellNow() messages are not queued.
     *
     * When using ActorSystem's WorkStealingPool (ControllableWorkStealingPool),
     * this method will also cancel CPU-bound jobs that are waiting in the pool's queue.
     * Jobs that are already executing will continue to completion.
     *
     * @return the total number of messages and jobs that were cleared
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
     * // Queue CPU-bound jobs
     * for (int i = 0; i < 100; i++) {
     *     counter.tell(c -> c.heavyComputation(), system.getWorkStealingPool());
     * }
     *
     * // Clear all pending messages and jobs
     * int cleared = counter.clearPendingMessages();
     * System.out.println("Cleared " + cleared + " messages and jobs");
     * // Clears both message queue and WorkStealingPool jobs
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
        // Clear message queue
        int clearedFromQueue = messageQueue.size();
        messageQueue.clear();

        // Cancel WorkStealingPool jobs if using ControllableWorkStealingPool
        int clearedFromPool = 0;
        if (this.actorSystem != null) {
            ExecutorService pool = this.actorSystem.getWorkStealingPool();
            if (pool instanceof ControllableWorkStealingPool) {
                ControllableWorkStealingPool controllablePool = (ControllableWorkStealingPool) pool;
                clearedFromPool = controllablePool.cancelJobsForActor(this.actorName);
            }
        }

        int total = clearedFromQueue + clearedFromPool;
        logger.log(Level.INFO, "Cleared " + total + " pending messages from actor: " + actorName +
                   " (queue: " + clearedFromQueue + ", pool: " + clearedFromPool + ")");
        return total;
    }

}
