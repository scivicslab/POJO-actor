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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
     * @since 4.9.0
     */
    public boolean ownsCurrentThread() {
        return Thread.currentThread() instanceof PoolThread poolThread && poolThread.pool == this;
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
