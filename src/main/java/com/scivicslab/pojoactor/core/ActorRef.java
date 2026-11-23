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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.ArrayDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * The actors whose turn the current thread holds: one of their messages, or the pool action
     * of one of their messages, is running on it. An actor's turn is held by its receive loop's
     * virtual thread while it runs a message, and by the real thread of a {@code ManagedThreadPool}
     * that runs the action of a message given that pool.
     */
    private static final ThreadLocal<ArrayDeque<ActorRef<?>>> TURNS = ThreadLocal.withInitial(ArrayDeque::new);

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
     * Whether the caller is running on this actor's own mailbox virtual thread, inside one of the
     * messages it has queued.
     *
     * <p>A caller that gets {@code true} must not ask or tell this actor and wait for the answer:
     * the message would queue behind the one now running, which is the one waiting. It can do the
     * work where it is instead, as {@link ManagedThreadPool#ownsCurrentThread()} lets a task on a
     * pool real thread do.</p>
     *
     * @return true when {@code Thread.currentThread()} is this actor's mailbox virtual thread
     * @since 5.0.0
     */
    public boolean ownsCurrentThread() {
        return Thread.currentThread() == actorThread;
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
     *   <li>messages sent with {@code tell} and {@code ask}, with or without a pool, start and
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
     * Note: this method skips the mailbox. The action runs on its own virtual thread, at the same
     * time as the message the mailbox is running, as other tellNow/askNow work, and as any other
     * action on the object. Nothing serialises these, so with tellNow the object's thread safety
     * is the caller's responsibility.
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

        if (currentThreadHoldsTurn()) {
            // Asked from inside one of this actor's own messages: queueing it behind the running
            // message would make that message wait for itself. Run it now, nested, as a method
            // call would; the outer message waits inside this call, so still only one call runs.
            try {
                future.complete(action.apply(target));
            } catch (Throwable t) {
                completeExceptionally(future, t);
            }
            return future;
        }

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
     * Note: this method skips the mailbox. The function runs on its own virtual thread, at the
     * same time as the message the mailbox is running, as other tellNow/askNow work, and as any
     * other action on the object. Nothing serialises these, so with askNow the object's thread
     * safety is the caller's responsibility.
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
     * Sends a message to this actor whose action runs on a real thread of {@code ws}, in the actor's turn.
     *
     * <p>The message is queued in the actor's mailbox like any other. When the actor's receive
     * loop, on the actor's own virtual thread, reaches it, the loop hands the action to
     * {@code ws}, to one of its real threads, and waits, on its virtual thread, until the action has finished; only then does it take the next message.
     * So this message and every other message to this actor -- {@code tell}, {@code ask}, and
     * {@code tell}/{@code ask} with a pool -- run one at a time, in the order they were
     * sent: at most one thread is inside the object at any time. The CPU work runs on the
     * pool's real thread, and the actor's virtual thread, which only waits, holds no real thread.
     * Heavy work given here therefore delays the actor's later messages; to run something at the
     * same time as the actor's messages, use {@link #tellNow} or {@link #askNow}.</p>
     *
     * <p>When the caller is one of the pool's own real threads ({@link ManagedThreadPool#ownsCurrentThread()}),
     * the message is still queued and taken in turn, and the pool real thread that waits for it
     * (through the returned future's {@code get} or {@code join}) runs the action itself when the
     * receive loop hands it back. While it waits it also runs other queued tasks of its pool, so
     * that a pool whose every real thread waits this way still makes progress.</p>
     *
     * <p>A {@code tell} to this actor from inside one of its own messages is queued after the
     * current message.</p>
     *
     * <p>Not guaranteed: two actors that ask each other from inside their own messages wait for
     * each other for ever, as with plain {@link #ask(Function)}; and a pool real thread that waits
     * on a stage derived from the returned future (for example through {@code thenApply} or
     * {@code allOf}) waits without helping, so it can deadlock a pool whose real threads all wait
     * that way. Wait on the returned future itself, or through
     * {@link ManagedThreadPool#awaitHelping(CompletableFuture)}.</p>
     *
     * @param action the action to execute on this actor's object
     * @param ws the managed thread pool whose real thread runs the action
     * @return a CompletableFuture that completes when the action has run
     */
    public CompletableFuture<Void> tell(Consumer<T> action, ManagedThreadPool ws) {
        return inTurnOn(ws, target -> {
            action.accept(target);
            return null;
        }, false);
    }

    /**
     * Sends a message to this actor whose function runs on a thread of {@code ws}, in the actor's
     * turn, and returns its result.
     *
     * <p>Everything said for {@link #tell(Consumer, ManagedThreadPool)} holds: the message is taken
     * in the actor's turn and order, its function runs on a real thread of {@code ws} while the actor's virtual thread waits, and at most one
     * thread is inside the object at any time.</p>
     *
     * <p>Asked from inside one of this actor's own messages -- the calling thread holds the
     * actor's turn -- the function runs at once, nested, as a method call would: on the calling
     * thread when that is one of the pool's own real threads, and otherwise on a real thread of
     * {@code ws} while the calling thread waits. Queued behind the running message, it would make
     * that message wait for itself.</p>
     *
     * @param <R> the type of the response
     * @param action the function to execute on this actor's object
     * @param ws the managed thread pool whose real thread runs the function
     * @return a CompletableFuture containing the response from the function
     */
    public <R> CompletableFuture<R> ask(Function<T, R> action, ManagedThreadPool ws) {
        return inTurnOn(ws, action, true);
    }

    /** Whether the current thread holds this actor's turn. */
    private boolean currentThreadHoldsTurn() {
        return TURNS.get().contains(this);
    }

    private void enterTurn() {
        TURNS.get().push(this);
    }

    private void leaveTurn() {
        TURNS.get().pop();
    }

    /**
     * The one path for an action given a pool: queued in the mailbox and run in the actor's
     * turn by {@link #runInTurnOn}, or, for an ask from inside the actor's own turn, nested.
     */
    private <R> CompletableFuture<R> inTurnOn(ManagedThreadPool ws, Function<T, R> action, boolean waitsForAnswer) {
        if (!running.get()) {
            return closedFuture();
        }
        T target = this.object;

        if (waitsForAnswer && currentThreadHoldsTurn()) {
            return nested(ws, action, target);
        }

        CompletableFuture<R> future = new PoolFuture<>(ws);
        boolean handBack;
        if (ws.ownsCurrentThread()) {
            // The inline rule: the caller is one of the pool's own real threads. It does not run
            // the action here, which would skip the actor's turn; the message is queued like any
            // other, and the receive loop hands the action back to this caller when it waits.
            handBack = true;
        } else {
            handBack = false;
        }
        messageQueue.offer(new Message(() -> runInTurnOn(ws, action, target, future, handBack), future));
        failQueuedIfClosed();
        return future;
    }

    /**
     * Runs, on the receive loop's virtual thread and in the actor's turn, a message whose action
     * goes to a pool: hands the action to {@code ws} and waits until it has finished.
     */
    private <R> void runInTurnOn(ManagedThreadPool ws, Function<T, R> action, T target,
                                 CompletableFuture<R> future, boolean handBack) {
        if (!running.get()) {
            future.completeExceptionally(new ActorClosedException(actorName));
            return;
        }
        TurnJob job = new TurnJob(() -> {
            enterTurn();
            try {
                if (!running.get()) {
                    future.completeExceptionally(new ActorClosedException(actorName));
                    return;
                }
                future.complete(action.apply(target));
            } catch (Throwable t) {
                completeExceptionally(future, t);
            } finally {
                leaveTurn();
            }
        });
        // Completed from outside before it started -- cancelled by clearPendingMessages(), or
        // failed when its pool shut down: it must not run any more, and the turn goes on.
        future.whenComplete((result, failure) -> job.abandon());
        if (handBack) {
            ((PoolFuture<R>) future).handBack(job);
        }
        try {
            ws.submitForActor(actorName, job, future);
        } catch (RuntimeException refused) {
            if (job.abandon()) {
                future.completeExceptionally(running.get() ? refused : new ActorClosedException(actorName));
            }
        }
        awaitEnd(job, future);
    }

    /**
     * Waits, on the receive loop's virtual thread, for a job handed to a pool to end. The
     * interrupt from close() does not end the wait for a job that has started: it runs to the
     * end, and close() waits for this thread. A job that has not started is abandoned, and its
     * future fails with {@link ActorClosedException}.
     */
    private void awaitEnd(TurnJob job, CompletableFuture<?> future) {
        while (true) {
            try {
                job.ended.await();
                return;
            } catch (InterruptedException e) {
                if (!running.get() && job.abandon()) {
                    future.completeExceptionally(new ActorClosedException(actorName));
                    return;
                }
            }
        }
    }

    /**
     * An ask from inside this actor's own turn, run at once: on the calling thread when it is one
     * of the pool's real threads, otherwise on a real thread of {@code ws} while the calling thread
     * waits.
     */
    private <R> CompletableFuture<R> nested(ManagedThreadPool ws, Function<T, R> action, T target) {
        CompletableFuture<R> future = new CompletableFuture<>();
        Runnable run = () -> {
            enterTurn();
            try {
                future.complete(action.apply(target));
            } catch (Throwable t) {
                completeExceptionally(future, t);
            } finally {
                leaveTurn();
            }
        };
        if (ws.ownsCurrentThread()) {
            run.run();
            return future;
        }
        try {
            ws.submitForActor(actorName, run, future);
        } catch (RuntimeException refused) {
            future.completeExceptionally(refused);
            return future;
        }
        boolean interrupted = false;
        while (!future.isDone()) {
            try {
                future.get();
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (ExecutionException | java.util.concurrent.CancellationException e) {
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return future;
    }

    /**
     * An action handed to a pool in the actor's turn. Run at most once: by a thread of the
     * pool, by the waiting caller it was handed back to, or not at all when it is abandoned
     * first.
     */
    private static final class TurnJob implements Runnable {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch ended = new CountDownLatch(1);
        private final Runnable body;

        TurnJob(Runnable body) {
            this.body = body;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                body.run();
            } finally {
                ended.countDown();
            }
        }

        /** Claims the job so that it never runs. @return true when it had not started */
        boolean abandon() {
            if (claimed.compareAndSet(false, true)) {
                ended.countDown();
                return true;
            }
            return false;
        }
    }

    /**
     * The future of a message given a {@link ManagedThreadPool}. Waited on through {@code get} or
     * {@code join} by one of that pool's real threads, it helps: the waiting real thread runs the
     * action handed back to it, and other queued tasks of its pool, until the future completes.
     * Stages derived from it are plain futures and do not help.
     */
    static final class PoolFuture<R> extends CompletableFuture<R> {
        private final ManagedThreadPool pool;
        private final AtomicReference<Runnable> handedBack = new AtomicReference<>();

        PoolFuture(ManagedThreadPool pool) {
            this.pool = pool;
        }

        /** Hands the action back to the pool real thread that sent it, for when it waits. */
        void handBack(Runnable job) {
            handedBack.set(job);
            pool.wakeHelpers();
        }

        @Override
        public R get() throws InterruptedException, ExecutionException {
            if (!isDone() && pool.ownsCurrentThread()) {
                pool.awaitHelping(this, handedBack, true, false, 0L);
            }
            return super.get();
        }

        @Override
        public R get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (!isDone() && pool.ownsCurrentThread()) {
                long deadline = System.nanoTime() + unit.toNanos(timeout);
                if (!pool.awaitHelping(this, handedBack, true, true, deadline)) {
                    throw new TimeoutException();
                }
            }
            return super.get(timeout, unit);
        }

        @Override
        public R join() {
            if (!isDone() && pool.ownsCurrentThread()) {
                try {
                    pool.awaitHelping(this, handedBack, false, false, 0L);
                } catch (InterruptedException e) {
                    throw new IllegalStateException("an uninterruptible wait was interrupted", e);
                }
            }
            return super.join();
        }
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
     * <p>Stops the receive loop. The message running when close() is called is waited for, up to
     * five seconds: a plain message on the actor's virtual thread is interrupted, and a message
     * whose action runs on a pool real thread is not interrupted -- the
     * receive loop goes on waiting for that action, and close() for the receive loop. Every
     * message still queued, those given a pool included, fails with
     * {@link ActorClosedException}; so does such a message whose action had not yet started on
     * its pool. A {@code tell}, {@code ask}, {@code tellNow} or {@code askNow} sent
     * afterwards queues nothing and returns a future already failed with
     * {@code ActorClosedException}. Then the actor's registrations are removed from its system.</p>
     *
     * <p>When close() is called from inside one of this actor's own messages -- on its virtual
     * thread, or in the pool action of one of its messages -- the receive loop is neither
     * interrupted nor waited for; it ends when that message returns.</p>
     *
     * <p>{@code tellNow} and {@code askNow} work, which runs on its own virtual thread outside the
     * receive loop, checks just before it calls the actor whether the actor is still open; if
     * not, its future fails with {@code ActorClosedException}. Such work that has already started
     * is not interrupted: it runs to the end, and close() waits for it within the same five
     * seconds. Work running on the thread that calls close() is not waited for. So no action of
     * this actor starts after close() has returned.</p>
     *
     * <p>This method implements the AutoCloseable interface for resource management.</p>
     */
    @Override
    public void close() {
        stop();
        awaitStopped();
    }

    /**
     * The first half of {@link #close()}: marks the actor closed and interrupts its receive loop.
     * Once this has returned, no message of this actor starts, and no tellNow/askNow work of it
     * calls the object.
     *
     * <p>{@link ActorSystem#terminate()} stops every actor before it waits for any, so that while
     * it waits for one actor's running message, another actor's queued message cannot start.</p>
     */
    void stop() {
        // Stop the message processing loop
        running.set(false);

        // Interrupt the actor thread to wake it up from blocking queue operations -- unless the
        // caller holds this actor's turn: it is then an action on that very virtual thread, or
        // the pool action the receive loop is waiting for. Interrupting would leave the interrupt
        // flag set for the rest of the action. The loop ends by itself when the action returns,
        // since running is now false.
        if (actorThread != null && !currentThreadHoldsTurn()) {
            actorThread.interrupt();
        }
    }

    /**
     * The second half of {@link #close()}: waits, up to five seconds in all, for the running
     * tellNow/askNow work and for the receive loop -- which itself waits for a pool action that
     * has started -- to end; then fails the messages still queued, drops the object and removes
     * the actor's registrations.
     */
    void awaitStopped() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS);
        try {
            // tellNow/askNow work that has started runs to the end; it is not interrupted.
            awaitRunningNowWork(deadline);
            // The receive loop waits for its running message, also one whose action runs on a
            // pool real thread; that action is not interrupted either. When the caller holds the
            // turn, the loop is waiting for the caller itself: joining it would wait for itself.
            if (actorThread != null && !currentThreadHoldsTurn()) {
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
                        enterTurn();
                        try {
                            message.body().run();
                        } finally {
                            leaveTurn();
                        }
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
            clearedFromPool = actorSystem.getManagedThreadPool().cancelJobsForActor(actorName);
        }

        logger.log(Level.INFO, "Cleared " + clearedFromQueue + " messages and " + clearedFromPool + " pool jobs from actor: " + actorName);
        return clearedFromQueue + clearedFromPool;
    }

}
