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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
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
        super(
            parallelism,                          // corePoolSize
            parallelism,                          // maximumPoolSize
            0L,                                   // keepAliveTime
            TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<>()           // workQueue - allows front insertion
        );
        this.actorTasks = new ConcurrentHashMap<>();
    }

    /**
     * Submits a task associated with a specific actor.
     * The task is added to the end of the queue (normal priority).
     *
     * @param actorName the name of the actor submitting this task
     * @param task the task to execute
     */
    public void submitForActor(String actorName, Runnable task) {
        CopyOnWriteArrayList<Runnable> tasks = actorTasks.computeIfAbsent(actorName, (String k) -> new CopyOnWriteArrayList<>());

        Runnable wrappedTask = new Runnable() {
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
        };

        synchronized (tasks) {
            tasks.add(wrappedTask);
        }

        execute(wrappedTask);
    }

    /**
     * Submits an urgent task to the front of the queue.
     * The task will be executed before other pending tasks.
     *
     * @param actorName the name of the actor submitting this task
     * @param task the urgent task to execute
     */
    public void submitUrgentForActor(String actorName, Runnable task) {
        CopyOnWriteArrayList<Runnable> tasks = actorTasks.computeIfAbsent(actorName, (String k) -> new CopyOnWriteArrayList<>());

        Runnable wrappedTask = new Runnable() {
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
        };

        synchronized (tasks) {
            tasks.add(wrappedTask);
        }

        LinkedBlockingDeque<Runnable> deque = (LinkedBlockingDeque<Runnable>) getQueue();
        deque.offerFirst(wrappedTask);
    }

    @Override
    public boolean supportsCancellation() {
        return true;
    }

    /**
     * Cancels all pending jobs for a specific actor.
     * Jobs that are already running will continue to completion.
     * Only jobs that are still in the queue will be removed.
     *
     * @param actorName the name of the actor whose jobs should be cancelled
     * @return the number of jobs that were cancelled
     */
    @Override
    public int cancelJobsForActor(String actorName) {
        CopyOnWriteArrayList<Runnable> tasks = actorTasks.get(actorName);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        int cancelled = 0;
        BlockingQueue<Runnable> queue = getQueue();

        for (Runnable task : tasks) {
            if (queue.remove(task)) {
                cancelled++;
            }
        }

        tasks.clear();
        actorTasks.remove(actorName);

        logger.log(Level.INFO, "Cancelled " + cancelled + " jobs for actor: " + actorName);
        return cancelled;
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
