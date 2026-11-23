/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scivicslab.pojoactor.core;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A managed thread pool for CPU-intensive tasks in POJO-actor.
 *
 * <p>Virtual threads in POJO-actor excel at lightweight operations (message passing,
 * state updates), but CPU-intensive computations should be delegated to a real
 * thread pool to avoid blocking the virtual thread scheduler.</p>
 *
 * <p>This class provides a managed pool of real OS threads with actor-level job
 * management capabilities:</p>
 * <ul>
 *   <li>Track jobs per actor</li>
 *   <li>Cancel all pending jobs for a specific actor</li>
 *   <li>Submit urgent jobs to the front of the queue</li>
 *   <li>Query pending job count per actor</li>
 * </ul>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * ActorSystem system = new ActorSystem("system", 4); // 4 CPU threads
 *
 * // Light operation → virtual thread (default)
 * actor.tell(a -> a.updateCounter());
 *
 * // Heavy computation → managed thread pool
 * CompletableFuture<Double> result = actor.ask(
 *     a -> a.performMatrixMultiplication(),
 *     system.getManagedThreadPool()
 * );
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.12.0
 */
public class ManagedThreadPool extends ThreadPoolExecutor implements WorkerPool {

    private static final Logger logger = Logger.getLogger(ManagedThreadPool.class.getName());

    // Track which tasks belong to which actor
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>> actorTasks;

    /** Signalled when a task is queued, a task is handed back, or a future a waiting real thread waits on completes. */
    private final Object helpSignal = new Object();

    /** Counts the signals, so that a waiting real thread does not miss one sent while it looked at the queue. */
    private final AtomicLong helpSignals = new AtomicLong();

    /** How many of this pool's real threads are in {@link #awaitHelping}. */
    private final AtomicInteger helpersWaiting = new AtomicInteger();

    /**
     * The largest number of tasks of other callers that one real thread, holding no actor's turn,
     * runs nested in its own stack while it waits (see {@link #awaitHelping(CompletableFuture)}). A
     * real thread that has reached it runs only what a real thread that holds a turn runs.
     *
     * <p>The value is 32. With the limit removed and tasks that do nothing else, one real thread of
     * a pool nested 984 tasks before its stack ended the nesting (one measurement, GraalVM CE 25, the
     * default stack size of a real thread). A limit of 32 uses about 3 percent of that stack and leaves
     * the rest to the code of the nested tasks.</p>
     */
    static final int MAX_HELPING_DEPTH = 32;

    /** Per real thread: how many tasks, own or others', it runs nested in its stack while waiting. */
    private static final ThreadLocal<int[]> HELPING_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** The deepest nesting any real thread of this pool has reached; read by the tests. */
    private final AtomicInteger deepestHelping = new AtomicInteger();

    /**
     * Creates a ManagedThreadPool with the specified parallelism level.
     *
     * @param parallelism the number of threads in the pool (typically matches CPU core count)
     */
    public ManagedThreadPool(int parallelism) {
        this(parallelism, new PoolThreadFactory());
    }

    private ManagedThreadPool(int parallelism, PoolThreadFactory threadFactory) {
        super(
            parallelism,                          // corePoolSize
            parallelism,                          // maximumPoolSize
            0L,                                   // keepAliveTime
            TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<>(),          // workQueue - allows front insertion
            threadFactory
        );
        // The pool's real threads are made lazily, on the first execute(), so the factory knows
        // its owner before it makes any of them.
        threadFactory.owner = this;
        this.actorTasks = new ConcurrentHashMap<>();
    }

    /**
     * Whether the caller is running on one of this pool's own real threads.
     *
     * <p>A task already running on one of these real threads that submits more work to this pool
     * and waits for it can deadlock: with every real thread of the pool waiting the same way,
     * nothing is left to run the submitted work. A caller that gets {@code true} here can run the
     * work where it is instead (as {@code ActorRef.ask(action, pool)} does).</p>
     *
     * @return true when {@code Thread.currentThread()} is a real thread of this pool
     * @since 5.0.0
     */
    public boolean ownsCurrentThread() {
        return Thread.currentThread() instanceof PoolThread poolThread && poolThread.pool == this;
    }

    /**
     * Waits until {@code future} completes; when the caller is one of this pool's own real
     * threads, it runs queued tasks of this pool while it waits.
     *
     * <p>A real thread of this pool that just blocks on a future holds one of the pool's real
     * threads while the work it waits for needs one. When every real thread of the pool blocks that
     * way, no real thread is left to run that work, and the pool stops. Waiting here instead, the
     * real thread takes a queued task of its pool, runs it on its own stack, and looks again,
     * until the future has completed. The futures returned by {@code ActorRef.ask(action, pool)} and
     * {@code ActorRef.tell(action, pool)} wait this way in their own {@code get} and {@code join}.
     * A real thread of this pool that waits on any other future, for example on
     * {@code CompletableFuture.allOf(...)} or on a stage derived from such a future, does not wait
     * this way by itself, and should call this method.</p>
     *
     * <p>Rules, each pinned by {@code ManagedThreadPoolHelpingTest}:</p>
     * <ul>
     *   <li><b>Whose tasks.</b> A real thread that holds no actor's turn takes any queued task of its
     *       pool, in queue order. A real thread that holds an actor's turn, that is, a thread inside the
     *       action of a message of an actor, runs only the tasks that its wait depends on: the action
     *       that {@code ActorRef} hands back to it, and, when the future is the answer of an actor's
     *       message, the queued tasks of that actor, since the answer comes behind them. It leaves every
     *       other task to other real threads. Running an unrelated task on top of a held turn keeps
     *       that turn held until the unrelated task ends, and two real threads that each wait, on top
     *       of a held turn, for an actor whose turn the other holds below, wait for each other for
     *       ever. A wait on a future that is not the answer of an actor's message, made by a real
     *       thread that holds a turn, therefore runs no task of the pool; wait on the answers of the
     *       actors instead.</li>
     *   <li><b>Per actor.</b> The real thread never takes a task of an actor whose turn it holds,
     *       at any depth of its stack: that task would be a second call into that actor's object on the
     *       same stack. Tasks are taken in queue order, so the tasks of one actor keep the order
     *       they were queued in.</li>
     *   <li><b>Depth.</b> A real thread that holds no turn runs at most 32
     *       tasks of other callers nested in its stack. A task it runs can wait in the same way and run
     *       another, and so on; at the limit the real thread runs only what a real thread that holds a
     *       turn runs. The stack therefore does not grow with the number of queued tasks, and grows
     *       beyond the limit only along a chain of actors that wait for each other. A pool whose real
     *       threads all stand at the limit, each waiting for a queued task that is not one of those,
     *       makes no progress, as a pool without this wait would not.</li>
     *   <li><b>Own actor.</b> Asked from inside the actor's own turn, an {@code ask} runs nested at once
     *       (see {@code ActorRef}) and never waits here for its own actor's mailbox.</li>
     *   <li><b>Interrupt.</b> The interrupt status of the real thread belongs to the caller that waits.
     *       The tasks it runs start with the status cleared. After a task, an interrupt that came
     *       while the task ran, or that the task left set, ends this wait with an
     *       {@link InterruptedException} (the status is cleared, as for any thrown
     *       {@code InterruptedException}); in the waits that are not interruptible the interrupt is
     *       remembered and set again when the wait returns.</li>
     *   <li><b>Thread.</b> A task run here runs on the waiting real thread, which is a real thread of
     *       this pool: {@link #ownsCurrentThread()} is true inside it.</li>
     * </ul>
     *
     * <p>A task that has started runs to its end. When the future completes while the real thread
     * is inside a task, this method returns after that task, so a long task delays the return.</p>
     *
     * <p>Called from a thread that is not a real thread of this pool, it just waits.</p>
     *
     * @param future the future to wait for
     * @throws InterruptedException if the calling thread is interrupted while it waits
     * @since 5.0.0
     */
    public void awaitHelping(CompletableFuture<?> future) throws InterruptedException {
        awaitHelping(future, null, null, true, false, 0L);
    }

    /**
     * Waits like {@link #awaitHelping(CompletableFuture)}, for at most {@code timeout}.
     *
     * <p>The time limit is checked between the tasks the real thread runs. A task that has started
     * is not cut short, so the call can return after the limit by as long as the task runs.</p>
     *
     * @param future the future to wait for
     * @param timeout how long to wait at most; zero or negative: do not wait
     * @return true when the future has completed, false when the time ran out first
     * @throws InterruptedException if the calling thread is interrupted while it waits
     * @since 5.0.0
     */
    public boolean awaitHelping(CompletableFuture<?> future, Duration timeout) throws InterruptedException {
        long deadline;
        try {
            deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        } catch (ArithmeticException tooLong) {
            // longer than a long holds in nanoseconds (about 292 years): wait without a limit
            awaitHelping(future, null, null, true, false, 0L);
            return true;
        }
        return awaitHelping(future, null, null, true, true, deadline);
    }

    /**
     * Waits until {@code future} completes, helping when the caller is one of this pool's real
     * threads: first runs the task handed back in {@code handedBack}, if any, then queued tasks of
     * this pool, under the rules of {@link #awaitHelping(CompletableFuture)}.
     *
     * @param future the future to wait for
     * @param handedBack a task handed to this waiter in particular, or null
     * @param waitedActor the name of the actor whose message {@code future} answers, or null
     * @param interruptible whether an interrupt ends the wait; when false, the interrupt is set again afterwards
     * @param timed whether {@code deadline} applies
     * @param deadline the {@link System#nanoTime()} at which to give up, when {@code timed}
     * @return true when the future has completed, false when the deadline passed first
     * @throws InterruptedException when {@code interruptible} and the caller is interrupted
     */
    boolean awaitHelping(CompletableFuture<?> future, AtomicReference<Runnable> handedBack, String waitedActor,
                         boolean interruptible, boolean timed, long deadline) throws InterruptedException {
        if (!ownsCurrentThread()) {
            return awaitWithoutHelping(future, interruptible, timed, deadline);
        }
        future.whenComplete((result, failure) -> wakeHelpers());
        helpersWaiting.incrementAndGet();
        boolean interrupted = false;
        try {
            while (!future.isDone()) {
                // The interrupt status is the waiting caller's, whoever set it: a helped task that
                // ran, a shutdownNow of this pool, or the caller's own thread.
                if (Thread.interrupted()) {
                    if (interruptible) {
                        throw new InterruptedException();
                    }
                    interrupted = true;
                }
                if (timed && deadline - System.nanoTime() <= 0) {
                    return false;     // checked before every task, so that a long queue does not outlast the time limit
                }
                long seen = helpSignals.get();
                Runnable own = handedBack == null ? null : handedBack.getAndSet(null);
                if (own != null) {
                    runHere(own);
                    continue;
                }
                boolean onlyWhatTheWaitDependsOn = HELPING_DEPTH.get()[0] >= MAX_HELPING_DEPTH
                        || ActorRef.currentThreadHoldsAnyTurn();
                Runnable other = takeHelpable(waitedActor, onlyWhatTheWaitDependsOn);
                if (other != null) {
                    runHere(other);
                    continue;
                }
                synchronized (helpSignal) {
                    if (future.isDone() || helpSignals.get() != seen) {
                        continue;
                    }
                    try {
                        if (timed) {
                            long left = deadline - System.nanoTime();
                            if (left <= 0) {
                                return false;
                            }
                            TimeUnit.NANOSECONDS.timedWait(helpSignal, left);
                        } else {
                            helpSignal.wait();
                        }
                    } catch (InterruptedException e) {
                        if (interruptible) {
                            throw e;
                        }
                        interrupted = true;
                    }
                }
            }
            return true;
        } finally {
            helpersWaiting.decrementAndGet();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean awaitWithoutHelping(CompletableFuture<?> future, boolean interruptible,
                                               boolean timed, long deadline) throws InterruptedException {
        boolean interrupted = false;
        try {
            while (!future.isDone()) {
                try {
                    if (timed) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            return false;
                        }
                        future.get(left, TimeUnit.NANOSECONDS);
                    } else {
                        future.get();
                    }
                } catch (InterruptedException e) {
                    if (interruptible) {
                        throw e;
                    }
                    interrupted = true;
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException
                         | java.util.concurrent.TimeoutException e) {
                    // the future completed (or the deadline is checked again above)
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Takes, out of the queue, the first task that the calling real thread may run while it waits:
     * not a task of an actor whose turn this real thread holds; and, when {@code onlyWhatTheWaitDependsOn},
     * only a task of the actor {@code waitedActor}.
     *
     * @param waitedActor the actor whose message the awaited future answers, or null
     * @param onlyWhatTheWaitDependsOn whether the calling real thread holds a turn or has reached the depth limit
     * @return the task, or null when the queue holds none the calling thread may run
     */
    private Runnable takeHelpable(String waitedActor, boolean onlyWhatTheWaitDependsOn) {
        BlockingQueue<Runnable> queue = getQueue();
        for (Runnable queued : queue) {
            if (queued instanceof ActorTask actorTask) {
                if (ActorRef.currentThreadHoldsTurnOf(actorTask.actorName)) {
                    continue;
                }
                if (onlyWhatTheWaitDependsOn && !actorTask.actorName.equals(waitedActor)) {
                    continue;
                }
            } else if (onlyWhatTheWaitDependsOn) {
                continue;
            }
            if (queue.remove(queued)) {
                return queued;
            }
        }
        return null;
    }

    /** Runs a task on the waiting real thread, counting the nesting, as a worker of the pool would run it. */
    private void runHere(Runnable task) {
        int[] depth = HELPING_DEPTH.get();
        depth[0]++;
        deepestHelping.accumulateAndGet(depth[0], Math::max);
        try {
            task.run();
        } catch (StackOverflowError overflow) {
            logger.log(Level.WARNING, "A task run by a waiting real thread overflowed the stack", overflow);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "A task run by a waiting real thread threw", t);
        } finally {
            depth[0]--;
        }
    }

    /**
     * The deepest nesting that a real thread of this pool has reached while it waited: the number of
     * tasks, its own handed-back action included, run one inside another on one stack. For the tests.
     *
     * @return the deepest nesting so far
     */
    int deepestHelpingDepth() {
        return deepestHelping.get();
    }

    /** Wakes the real threads waiting in {@link #awaitHelping}: there may be work for them. */
    void wakeHelpers() {
        helpSignals.incrementAndGet();
        if (helpersWaiting.get() > 0) {
            synchronized (helpSignal) {
                helpSignal.notifyAll();
            }
        }
    }

    /** Queues a task as {@link ThreadPoolExecutor#execute} does, and wakes waiting real threads. */
    @Override
    public void execute(Runnable command) {
        super.execute(command);
        wakeHelpers();
    }

    /** A real thread of a ManagedThreadPool, which knows the pool it belongs to. */
    private static final class PoolThread extends Thread {
        private final ManagedThreadPool pool;

        PoolThread(ManagedThreadPool pool, Runnable work, String name) {
            super(work, name);
            this.pool = pool;
        }
    }

    /**
     * Makes the pool's real threads as PoolThreads. They are named
     * {@code managed-pool-<pool number>-thread-<thread number>}, and are not daemons, as the
     * real threads of the default thread factory are not.
     */
    private static final class PoolThreadFactory implements java.util.concurrent.ThreadFactory {
        private static final java.util.concurrent.atomic.AtomicInteger POOL_NUMBER = new java.util.concurrent.atomic.AtomicInteger(1);
        private final int poolNumber = POOL_NUMBER.getAndIncrement();
        private final java.util.concurrent.atomic.AtomicInteger threadNumber = new java.util.concurrent.atomic.AtomicInteger(1);
        private volatile ManagedThreadPool owner;

        @Override
        public Thread newThread(Runnable work) {
            Thread thread = new PoolThread(owner, work,
                    "managed-pool-" + poolNumber + "-thread-" + threadNumber.getAndIncrement());
            thread.setDaemon(false);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }

    /**
     * Submits a task associated with a specific actor.
     * The task is added to the end of the queue (normal priority).
     *
     * @param actorName the name of the actor submitting this task
     * @param task the task to execute
     */
    public void submitForActor(String actorName, Runnable task) {
        submitForActor(actorName, task, null);
    }

    /**
     * Submits a task associated with a specific actor, together with the future its caller holds,
     * so that the future can be completed if the task is discarded before it runs: cancelled by
     * {@link #cancelJobsForActor(String)}, failed with {@link ActorClosedException} when the
     * actor system terminates.
     *
     * @param actorName the name of the actor submitting this task
     * @param task the task to execute
     * @param future the future the task completes when it runs, or null when there is none
     */
    void submitForActor(String actorName, Runnable task, CompletableFuture<?> future) {
        ActorTask wrappedTask = track(actorName, task, future);
        try {
            execute(wrappedTask);
        } catch (RuntimeException rejected) {
            // Not queued (the pool is shut down, for example): it must not stay tracked.
            untrack(wrappedTask);
            throw rejected;
        }
    }

    /**
     * Submits an urgent task to the front of the queue.
     * The task will be executed before other pending tasks.
     *
     * <p>The task goes into the queue directly, not through {@link #execute}, and
     * {@code execute} is what starts the pool's real threads. So this method starts a core
     * real thread itself when fewer than the pool's parallelism are running; otherwise the
     * urgent task given to a fresh pool would wait in a queue that no real thread reads.</p>
     *
     * @param actorName the name of the actor submitting this task
     * @param task the urgent task to execute
     * @throws RejectedExecutionException if the pool is shut down
     */
    public void submitUrgentForActor(String actorName, Runnable task) {
        if (isShutdown()) {
            throw new RejectedExecutionException(
                    "the pool is shut down; urgent task of actor " + actorName + " refused");
        }
        ActorTask wrappedTask = track(actorName, task, null);

        LinkedBlockingDeque<Runnable> deque = (LinkedBlockingDeque<Runnable>) getQueue();
        deque.offerFirst(wrappedTask);
        prestartCoreThread();
        if (isShutdown() && deque.remove(wrappedTask)) {
            // shut down while the task was being queued: no real thread may be left to run it
            synchronized (wrappedTask.tasks) {
                wrappedTask.tasks.remove(wrappedTask);
            }
            throw new RejectedExecutionException(
                    "the pool is shut down; urgent task of actor " + actorName + " refused");
        }
    }

    /**
     * Wraps a task so that it is tracked under its actor until it has run.
     *
     * <p>The task is added under the lock of its actor's list, and only while that list is still
     * the one registered for the actor: {@link #cancelJobsForActor(String)} may remove an empty
     * list from the map, and a task added to a removed list would be queued but tracked nowhere.</p>
     */
    private ActorTask track(String actorName, Runnable task, CompletableFuture<?> future) {
        while (true) {
            CopyOnWriteArrayList<Runnable> tasks = actorTasks.computeIfAbsent(actorName, (String k) -> new CopyOnWriteArrayList<>());
            synchronized (tasks) {
                if (actorTasks.get(actorName) == tasks) {
                    ActorTask wrappedTask = new ActorTask(actorName, task, future, tasks);
                    tasks.add(wrappedTask);
                    return wrappedTask;
                }
            }
        }
    }

    /** Takes a task that was never queued out of its actor's list again. */
    private void untrack(ActorTask wrappedTask) {
        synchronized (wrappedTask.tasks) {
            wrappedTask.tasks.remove(wrappedTask);
            if (wrappedTask.tasks.isEmpty()) {
                actorTasks.remove(wrappedTask.actorName, wrappedTask.tasks);
            }
        }
    }

    /**
     * A task queued for an actor: the code to run, the actor's name, and the future its caller
     * holds (null for a task submitted without one). Keeping the future here lets a task that is
     * thrown away before it runs still complete its future.
     */
    static final class ActorTask implements Runnable {
        private final String actorName;
        private final Runnable task;
        private final CompletableFuture<?> future;
        private final CopyOnWriteArrayList<Runnable> tasks;

        ActorTask(String actorName, Runnable task, CompletableFuture<?> future, CopyOnWriteArrayList<Runnable> tasks) {
            this.actorName = actorName;
            this.task = task;
            this.future = future;
            this.tasks = tasks;
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                synchronized (tasks) {
                    tasks.remove(this);
                }
            }
        }

        /** Cancels the caller's future: the task was discarded on request. */
        void cancel() {
            if (future != null) {
                future.cancel(false);
            }
        }

        /** Fails the caller's future with {@link ActorClosedException}: the pool is shutting down. */
        void failClosed() {
            if (future != null) {
                future.completeExceptionally(new ActorClosedException(actorName));
            }
        }
    }

    /**
     * Completes, with {@link ActorClosedException}, the futures of the actor tasks among
     * {@code dropped} -- the tasks {@link #shutdownNow()} returned because they never started.
     *
     * @param dropped the tasks that will never run
     */
    static void failClosed(java.util.List<Runnable> dropped) {
        for (Runnable r : dropped) {
            if (r instanceof ActorTask actorTask) {
                actorTask.failClosed();
            }
        }
    }

    @Override
    public boolean supportsCancellation() {
        return true;
    }

    /**
     * Cancels all pending jobs for a specific actor.
     * Jobs that are already running will continue to completion.
     * Only jobs that are still in the queue will be removed. The future of each removed job
     * that was submitted by {@code ActorRef.tell(action, pool)} or {@code ask(action, pool)} is
     * cancelled, so its caller sees a {@code CancellationException}.
     *
     * @param actorName the name of the actor whose jobs should be cancelled
     * @return the number of jobs that were cancelled
     */
    @Override
    public int cancelJobsForActor(String actorName) {
        CopyOnWriteArrayList<Runnable> tasks = actorTasks.get(actorName);
        if (tasks == null) {
            return 0;
        }

        // Under the list's lock, so that no task is tracked while the queued ones are taken out:
        // a task tracked meanwhile was dropped from the list although it was still queued, and
        // then ran although its actor's jobs had been cancelled. A task that is running stays in
        // the list until it has run.
        java.util.List<ActorTask> removed = new java.util.ArrayList<>();
        BlockingQueue<Runnable> queue = getQueue();
        synchronized (tasks) {
            for (Runnable task : tasks) {
                if (queue.remove(task)) {
                    tasks.remove(task);
                    if (task instanceof ActorTask actorTask) {
                        removed.add(actorTask);
                    }
                }
            }
            if (tasks.isEmpty()) {
                actorTasks.remove(actorName, tasks);
            }
        }

        // Cancel the futures outside the lock: cancelling runs the callers' dependent actions.
        removed.forEach(ActorTask::cancel);

        logger.log(Level.INFO, "Cancelled " + removed.size() + " jobs for actor: " + actorName);
        return removed.size();
    }

    /**
     * Gets the number of pending jobs for a specific actor.
     * This includes jobs in the queue but not currently executing jobs.
     *
     * @param actorName the name of the actor
     * @return the number of pending jobs
     */
    @Override
    public int getPendingJobCountForActor(String actorName) {
        CopyOnWriteArrayList<Runnable> tasks = actorTasks.get(actorName);
        if (tasks == null) {
            return 0;
        }

        BlockingQueue<Runnable> queue = getQueue();
        int count = 0;
        for (Runnable task : tasks) {
            if (queue.contains(task)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Gets the total number of actors currently tracked.
     *
     * @return the number of actors with pending or running jobs
     */
    public int getTrackedActorCount() {
        return actorTasks.size();
    }
}
