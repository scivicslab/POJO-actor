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

package com.scivicslab.pojoactor.core.scheduler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.core.ActorRef;

/**
 * A scheduler for periodic task execution with ActorRef.
 *
 * <p>This class provides scheduling capabilities for actors using lambda expressions.
 * Tasks are executed on actors via {@link ActorRef#ask(java.util.function.Function)},
 * ensuring thread-safe access to actor state.</p>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>{@code
 * ActorSystem system = new ActorSystem("my-system", 4);
 * Scheduler scheduler = new Scheduler();
 *
 * ActorRef<MyActor> actorRef = system.actorOf("myActor", new MyActor());
 *
 * // Schedule a task to run every 10 seconds
 * scheduler.scheduleAtFixedRate("health-check", actorRef,
 *     actor -> actor.checkHealth(),
 *     0, 10, TimeUnit.SECONDS);
 *
 * // Schedule with fixed delay (waits for completion before next run)
 * scheduler.scheduleWithFixedDelay("cleanup", actorRef,
 *     actor -> actor.cleanup(),
 *     60, 300, TimeUnit.SECONDS);
 *
 * // Schedule a one-time task
 * scheduler.scheduleOnce("init", actorRef,
 *     actor -> actor.initialize(),
 *     5, TimeUnit.SECONDS);
 *
 * // Cancel a task
 * scheduler.cancelTask("health-check");
 *
 * // Cleanup
 * scheduler.close();
 * }</pre>
 *
 * <p>For workflow-based scheduling with action names, use
 * {@link com.scivicslab.pojoactor.workflow.scheduler.SchedulerIIAR} instead.</p>
 *
 * @author devteam@scivics-lab.com
 * @since 2.11.0 (refactored from IIActorRef-based to ActorRef-based)
 * @see ActorRef
 */
public class Scheduler implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(Scheduler.class.getName());

    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks;

    /**
     * Constructs a new Scheduler with default thread pool size (2).
     */
    public Scheduler() {
        this(2);
    }

    /**
     * Constructs a new Scheduler with the specified thread pool size.
     *
     * @param poolSize the number of threads in the scheduler's thread pool
     */
    public Scheduler(int poolSize) {
        this.executor = Executors.newScheduledThreadPool(poolSize, (Runnable r) -> {
            Thread t = new Thread(r, "Scheduler-Worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduledTasks = new ConcurrentHashMap<>();
    }

    /**
     * Schedules a task to execute periodically at a fixed rate.
     *
     * <p>The task is executed on the target actor via {@code ActorRef.ask()},
     * ensuring thread-safe access to the actor's state.</p>
     *
     * <p><strong>Fixed Rate vs Fixed Delay:</strong></p>
     * <p>With {@code scheduleAtFixedRate}, executions are scheduled to start at
     * regular intervals (0, period, 2*period, 3*period, ...) regardless of how
     * long each execution takes. If an execution takes longer than the period,
     * the next execution starts immediately after the previous one completes
     * (no delay accumulation).</p>
     *
     * <p>Use this method when you need consistent timing intervals, such as
     * metrics collection or heartbeat checks at precise intervals.</p>
     *
     * <p>In contrast, {@link #scheduleWithFixedDelay} waits for a fixed delay
     * <em>after</em> each execution completes before starting the next one.</p>
     *
     * <pre>
     * scheduleAtFixedRate (period=100ms, task takes 30ms):
     * |task|          |task|          |task|
     * 0    30   100   130   200   230   300ms
     *      └─period─┘      └─period─┘
     * </pre>
     *
     * @param <T> the type of the actor
     * @param taskId unique identifier for this scheduled task
     * @param actorRef reference to the target actor
     * @param action the action to perform on the actor
     * @param initialDelay delay before first execution
     * @param period interval between successive execution starts
     * @param unit time unit for the delays
     * @return the taskId for reference
     * @see #scheduleWithFixedDelay
     */
    public <T> String scheduleAtFixedRate(String taskId, ActorRef<T> actorRef,
                                          Consumer<T> action,
                                          long initialDelay, long period, TimeUnit unit) {
        ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {
            executeOnActor(taskId, actorRef, action);
        }, initialDelay, period, unit);

        registerTask(taskId, task);

        logger.log(Level.INFO, String.format(
            "Scheduled at fixed rate: %s (initial=%d, period=%d %s)",
            taskId, initialDelay, period, unit));

        return taskId;
    }

    /**
     * Schedules a task to execute periodically with a fixed delay between executions.
     *
     * <p>The task is executed on the target actor via {@code ActorRef.ask()},
     * ensuring thread-safe access to the actor's state.</p>
     *
     * <p><strong>Fixed Delay vs Fixed Rate:</strong></p>
     * <p>With {@code scheduleWithFixedDelay}, the delay between the <em>termination</em>
     * of one execution and the <em>start</em> of the next is always {@code delay} time
     * units. This ensures that executions never overlap and there is always a guaranteed
     * rest period between executions.</p>
     *
     * <p>Use this method when you need to ensure a minimum gap between executions,
     * such as polling operations where you want to avoid overwhelming a resource,
     * or when each execution depends on external state that needs time to stabilize.</p>
     *
     * <p>In contrast, {@link #scheduleAtFixedRate} schedules executions at fixed
     * intervals regardless of execution duration.</p>
     *
     * <pre>
     * scheduleWithFixedDelay (delay=100ms, task takes 30ms):
     * |task|              |task|              |task|
     * 0    30        130  160        260  290ms
     *      └──delay──┘    └──delay──┘
     * </pre>
     *
     * @param <T> the type of the actor
     * @param taskId unique identifier for this scheduled task
     * @param actorRef reference to the target actor
     * @param action the action to perform on the actor
     * @param initialDelay delay before first execution
     * @param delay delay between termination of one execution and start of next
     * @param unit time unit for the delays
     * @return the taskId for reference
     * @see #scheduleAtFixedRate
     */
    public <T> String scheduleWithFixedDelay(String taskId, ActorRef<T> actorRef,
                                             Consumer<T> action,
                                             long initialDelay, long delay, TimeUnit unit) {
        ScheduledFuture<?> task = executor.scheduleWithFixedDelay(() -> {
            executeOnActor(taskId, actorRef, action);
        }, initialDelay, delay, unit);

        registerTask(taskId, task);

        logger.log(Level.INFO, String.format(
            "Scheduled with fixed delay: %s (initial=%d, delay=%d %s)",
            taskId, initialDelay, delay, unit));

        return taskId;
    }

    /**
     * Schedules a task to execute once after a specified delay.
     *
     * <p>The task will be executed once after the specified delay and then
     * automatically removed from the scheduled tasks.</p>
     *
     * @param <T> the type of the actor
     * @param taskId unique identifier for this scheduled task
     * @param actorRef reference to the target actor
     * @param action the action to perform on the actor
     * @param delay delay before execution
     * @param unit time unit for the delay
     * @return the taskId for reference
     */
    public <T> String scheduleOnce(String taskId, ActorRef<T> actorRef,
                                   Consumer<T> action,
                                   long delay, TimeUnit unit) {
        ScheduledFuture<?> task = executor.schedule(() -> {
            try {
                executeOnActor(taskId, actorRef, action);
            } finally {
                scheduledTasks.remove(taskId);
            }
        }, delay, unit);

        registerTask(taskId, task);

        logger.log(Level.INFO, String.format(
            "Scheduled once: %s (delay=%d %s)", taskId, delay, unit));

        return taskId;
    }

    /**
     * Cancels a scheduled task.
     *
     * <p>If the task is currently executing, it will be allowed to complete.
     * The task will not be executed again after cancellation.</p>
     *
     * @param taskId identifier of the task to cancel
     * @return true if the task was found and cancelled, false otherwise
     */
    public boolean cancelTask(String taskId) {
        ScheduledFuture<?> task = scheduledTasks.remove(taskId);
        if (task != null) {
            boolean cancelled = task.cancel(false);
            logger.log(Level.INFO, cancelled ?
                "Cancelled: " + taskId :
                "Task already completed or cancelled: " + taskId);
            return cancelled;
        }
        logger.log(Level.WARNING, "Task not found: " + taskId);
        return false;
    }

    /**
     * Returns the number of currently scheduled tasks.
     *
     * @return the number of active scheduled tasks
     */
    public int getScheduledTaskCount() {
        return scheduledTasks.size();
    }

    /**
     * Checks if a task with the given ID is currently scheduled.
     *
     * @param taskId the task identifier to check
     * @return true if the task exists and is not cancelled, false otherwise
     */
    public boolean isScheduled(String taskId) {
        ScheduledFuture<?> task = scheduledTasks.get(taskId);
        return task != null && !task.isCancelled() && !task.isDone();
    }

    /**
     * Shuts down the scheduler and cancels all scheduled tasks.
     *
     * <p>This method will attempt to gracefully shutdown the scheduler executor,
     * waiting up to 5 seconds for tasks to terminate.</p>
     */
    @Override
    public void close() {
        logger.log(Level.INFO, "Shutting down scheduler, cancelling " +
            scheduledTasks.size() + " scheduled tasks");

        // Cancel all scheduled tasks
        scheduledTasks.values().forEach((ScheduledFuture<?> task) -> task.cancel(false));
        scheduledTasks.clear();

        // Shutdown the executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.log(Level.WARNING,
                    "Scheduler did not terminate within 5 seconds, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.log(Level.WARNING,
                "Interrupted while waiting for scheduler termination", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== Private Helper Methods ==========

    /**
     * Executes an action on an actor via ask().
     */
    private <T> void executeOnActor(String taskId, ActorRef<T> actorRef, Consumer<T> action) {
        try {
            actorRef.ask((T actor) -> {
                action.accept(actor);
                return null;
            }).join();
        } catch (Exception e) {
            logger.log(Level.SEVERE,
                String.format("Error executing scheduled task %s: %s", taskId, e.getMessage()), e);
        }
    }

    /**
     * Registers a task, replacing any existing task with the same ID.
     */
    private void registerTask(String taskId, ScheduledFuture<?> task) {
        ScheduledFuture<?> oldTask = scheduledTasks.put(taskId, task);
        if (oldTask != null) {
            oldTask.cancel(false);
            logger.log(Level.INFO, "Replaced existing scheduled task: " + taskId);
        }
    }
}
