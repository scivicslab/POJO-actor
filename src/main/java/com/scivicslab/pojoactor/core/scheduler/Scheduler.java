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
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * A scheduler for periodic task execution in an actor system.
 *
 * <p>This class provides scheduling capabilities for actors, allowing tasks to be
 * executed at fixed rates, with fixed delays, or as one-time scheduled tasks.
 * It integrates with the POJO-actor framework by sending messages to actors via
 * their action names.</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * IIActorSystem system = new IIActorSystem("my-system");
 * Scheduler scheduler = new Scheduler(system);
 * IIActorRef<Scheduler> schedulerRef = new SchedulerIIAR("scheduler", scheduler, system);
 * system.addIIActor(schedulerRef);
 *
 * // Schedule a task to run every 10 seconds
 * schedulerRef.callByActionName("scheduleAtFixedRate",
 *     "task1,targetActor,actionName,args,0,10,SECONDS");
 *
 * // Cancel the task
 * schedulerRef.callByActionName("cancel", "task1");
 *
 * // Cleanup
 * scheduler.close();
 * }</pre>
 *
 * <p>This class implements {@link AutoCloseable} to ensure proper cleanup of
 * scheduled tasks and the executor service.</p>
 *
 * @author devteam@scivics-lab.com
 * @since 2.5.0
 * @see SchedulerIIAR
 * @see CallableByActionName
 */
public class Scheduler implements CallableByActionName, AutoCloseable {

    private static final Logger logger = Logger.getLogger(Scheduler.class.getName());

    private final ScheduledExecutorService schedulerExecutor;
    private final ActorSystem system;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks;

    /**
     * Constructs a new Scheduler with the specified actor system.
     *
     * <p>The scheduler uses a thread pool of size 2 for executing scheduled tasks.</p>
     *
     * @param system the actor system to use for looking up target actors
     */
    public Scheduler(ActorSystem system) {
        this(system, 2);
    }

    /**
     * Constructs a new Scheduler with the specified actor system and thread pool size.
     *
     * @param system the actor system to use for looking up target actors
     * @param poolSize the number of threads in the scheduler's thread pool
     */
    public Scheduler(ActorSystem system, int poolSize) {
        this.system = system;
        this.schedulerExecutor = Executors.newScheduledThreadPool(poolSize);
        this.scheduledTasks = new ConcurrentHashMap<>();
    }

    /**
     * Helper method to get an ActorRef, trying both IIActorRef and regular ActorRef.
     *
     * @param actorName the name of the actor to retrieve
     * @return the ActorRef, or null if not found
     */
    private ActorRef<?> getActorRef(String actorName) {
        // Try IIActorRef first if system is IIActorSystem
        if (system instanceof IIActorSystem) {
            IIActorRef<?> iiActor = ((IIActorSystem) system).getIIActor(actorName);
            if (iiActor != null) {
                return iiActor;
            }
        }
        // Fall back to regular ActorRef
        return system.getActor(actorName);
    }

    /**
     * Schedules a task to execute periodically at a fixed rate.
     *
     * <p>The task will be executed every {@code period} time units after the initial delay.
     * If a task execution takes longer than the period, subsequent executions may start
     * before the previous one completes.</p>
     *
     * @param taskId unique identifier for this scheduled task
     * @param targetActorName name of the target actor in the actor system
     * @param action action name to invoke on the target actor
     * @param args arguments to pass to the action
     * @param initialDelay delay before first execution
     * @param period interval between successive executions
     * @param unit time unit for the delays
     * @return result message indicating success or failure
     */
    public String scheduleAtFixedRate(String taskId, String targetActorName,
                                     String action, String args,
                                     long initialDelay, long period, TimeUnit unit) {
        ActorRef<?> target = getActorRef(targetActorName);
        if (target == null) {
            String msg = "Actor not found: " + targetActorName;
            logger.log(Level.WARNING, msg);
            return msg;
        }

        ScheduledFuture<?> task = schedulerExecutor.scheduleAtFixedRate(() -> {
            try {
                if (target instanceof IIActorRef) {
                    ((IIActorRef<?>)target).callByActionName(action, args);
                } else {
                    logger.log(Level.WARNING,
                        "Target actor is not IIActorRef, cannot call by action name: " + targetActorName);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE,
                    String.format("Error executing scheduled task %s", taskId), e);
            }
        }, initialDelay, period, unit);

        ScheduledFuture<?> oldTask = scheduledTasks.put(taskId, task);
        if (oldTask != null) {
            oldTask.cancel(false);
            logger.log(Level.INFO, "Replaced existing scheduled task: " + taskId);
        }

        String msg = String.format("Scheduled at fixed rate: %s (initial=%d, period=%d %s)",
            taskId, initialDelay, period, unit);
        logger.log(Level.INFO, msg);
        return msg;
    }

    /**
     * Schedules a task to execute periodically with a fixed delay between executions.
     *
     * <p>The delay between the termination of one execution and the start of the next
     * will be {@code delay} time units. This ensures that executions never overlap.</p>
     *
     * @param taskId unique identifier for this scheduled task
     * @param targetActorName name of the target actor in the actor system
     * @param action action name to invoke on the target actor
     * @param args arguments to pass to the action
     * @param initialDelay delay before first execution
     * @param delay delay between successive executions
     * @param unit time unit for the delays
     * @return result message indicating success or failure
     */
    public String scheduleWithFixedDelay(String taskId, String targetActorName,
                                        String action, String args,
                                        long initialDelay, long delay, TimeUnit unit) {
        ActorRef<?> target = getActorRef(targetActorName);
        if (target == null) {
            String msg = "Actor not found: " + targetActorName;
            logger.log(Level.WARNING, msg);
            return msg;
        }

        ScheduledFuture<?> task = schedulerExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (target instanceof IIActorRef) {
                    ((IIActorRef<?>)target).callByActionName(action, args);
                } else {
                    logger.log(Level.WARNING,
                        "Target actor is not IIActorRef, cannot call by action name: " + targetActorName);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE,
                    String.format("Error executing scheduled task %s", taskId), e);
            }
        }, initialDelay, delay, unit);

        ScheduledFuture<?> oldTask = scheduledTasks.put(taskId, task);
        if (oldTask != null) {
            oldTask.cancel(false);
            logger.log(Level.INFO, "Replaced existing scheduled task: " + taskId);
        }

        String msg = String.format("Scheduled with fixed delay: %s (initial=%d, delay=%d %s)",
            taskId, initialDelay, delay, unit);
        logger.log(Level.INFO, msg);
        return msg;
    }

    /**
     * Schedules a task to execute once after a specified delay.
     *
     * <p>The task will be executed once after the specified delay and then automatically
     * removed from the scheduled tasks map.</p>
     *
     * @param taskId unique identifier for this scheduled task
     * @param targetActorName name of the target actor in the actor system
     * @param action action name to invoke on the target actor
     * @param args arguments to pass to the action
     * @param delay delay before execution
     * @param unit time unit for the delay
     * @return result message indicating success or failure
     */
    public String scheduleOnce(String taskId, String targetActorName,
                              String action, String args,
                              long delay, TimeUnit unit) {
        ActorRef<?> target = getActorRef(targetActorName);
        if (target == null) {
            String msg = "Actor not found: " + targetActorName;
            logger.log(Level.WARNING, msg);
            return msg;
        }

        ScheduledFuture<?> task = schedulerExecutor.schedule(() -> {
            try {
                if (target instanceof IIActorRef) {
                    ((IIActorRef<?>)target).callByActionName(action, args);
                } else {
                    logger.log(Level.WARNING,
                        "Target actor is not IIActorRef, cannot call by action name: " + targetActorName);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE,
                    String.format("Error executing scheduled task %s", taskId), e);
            } finally {
                scheduledTasks.remove(taskId);
            }
        }, delay, unit);

        ScheduledFuture<?> oldTask = scheduledTasks.put(taskId, task);
        if (oldTask != null) {
            oldTask.cancel(false);
            logger.log(Level.INFO, "Replaced existing scheduled task: " + taskId);
        }

        String msg = String.format("Scheduled once: %s (delay=%d %s)",
            taskId, delay, unit);
        logger.log(Level.INFO, msg);
        return msg;
    }

    /**
     * Cancels a scheduled task.
     *
     * <p>If the task is currently executing, it will be allowed to complete.
     * The task will not be executed again after cancellation.</p>
     *
     * @param taskId identifier of the task to cancel
     * @return result message indicating success or failure
     */
    public String cancelTask(String taskId) {
        ScheduledFuture<?> task = scheduledTasks.remove(taskId);
        if (task != null) {
            boolean cancelled = task.cancel(false);
            String msg = cancelled ?
                "Cancelled: " + taskId :
                "Task already completed or cancelled: " + taskId;
            logger.log(Level.INFO, msg);
            return msg;
        }
        String msg = "Task not found: " + taskId;
        logger.log(Level.WARNING, msg);
        return msg;
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
     * Invokes an action on the scheduler by name with the given arguments.
     *
     * <p>Supported actions:</p>
     * <ul>
     * <li>{@code scheduleAtFixedRate} - Arguments: taskId,targetActor,action,args,initialDelay,period,unit</li>
     * <li>{@code scheduleWithFixedDelay} - Arguments: taskId,targetActor,action,args,initialDelay,delay,unit</li>
     * <li>{@code scheduleOnce} - Arguments: taskId,targetActor,action,args,delay,unit</li>
     * <li>{@code cancel} - Arguments: taskId</li>
     * <li>{@code getTaskCount} - Arguments: none (returns task count)</li>
     * <li>{@code isScheduled} - Arguments: taskId (returns true/false)</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param args comma-separated argument string
     * @return an {@link ActionResult} indicating success or failure with a message
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        try {
            switch (actionName) {
                case "scheduleAtFixedRate": {
                    // Format: taskId,targetActor,action,args,initialDelay,period,unit
                    String[] parts = args.split(",", 7);
                    if (parts.length < 7) {
                        return new ActionResult(false,
                            "Invalid arguments for scheduleAtFixedRate. Expected: taskId,targetActor,action,args,initialDelay,period,unit");
                    }
                    String result = scheduleAtFixedRate(
                        parts[0], parts[1], parts[2], parts[3],
                        Long.parseLong(parts[4]), Long.parseLong(parts[5]),
                        TimeUnit.valueOf(parts[6])
                    );
                    return new ActionResult(true, result);
                }

                case "scheduleWithFixedDelay": {
                    // Format: taskId,targetActor,action,args,initialDelay,delay,unit
                    String[] parts = args.split(",", 7);
                    if (parts.length < 7) {
                        return new ActionResult(false,
                            "Invalid arguments for scheduleWithFixedDelay. Expected: taskId,targetActor,action,args,initialDelay,delay,unit");
                    }
                    String result = scheduleWithFixedDelay(
                        parts[0], parts[1], parts[2], parts[3],
                        Long.parseLong(parts[4]), Long.parseLong(parts[5]),
                        TimeUnit.valueOf(parts[6])
                    );
                    return new ActionResult(true, result);
                }

                case "scheduleOnce": {
                    // Format: taskId,targetActor,action,args,delay,unit
                    String[] parts = args.split(",", 6);
                    if (parts.length < 6) {
                        return new ActionResult(false,
                            "Invalid arguments for scheduleOnce. Expected: taskId,targetActor,action,args,delay,unit");
                    }
                    String result = scheduleOnce(
                        parts[0], parts[1], parts[2], parts[3],
                        Long.parseLong(parts[4]), TimeUnit.valueOf(parts[5])
                    );
                    return new ActionResult(true, result);
                }

                case "cancel": {
                    String result = cancelTask(args);
                    return new ActionResult(true, result);
                }

                case "getTaskCount": {
                    int count = getScheduledTaskCount();
                    return new ActionResult(true, "Scheduled tasks: " + count);
                }

                case "isScheduled": {
                    boolean scheduled = isScheduled(args);
                    return new ActionResult(true, scheduled ? "true" : "false");
                }

                default:
                    String msg = "Unknown action: " + actionName;
                    logger.log(Level.WARNING, msg);
                    return new ActionResult(false, msg);
            }
        } catch (NumberFormatException e) {
            String msg = "Invalid number format in arguments: " + args;
            logger.log(Level.SEVERE, msg, e);
            return new ActionResult(false, msg);
        } catch (IllegalArgumentException e) {
            String msg = "Invalid argument: " + e.getMessage();
            logger.log(Level.SEVERE, msg, e);
            return new ActionResult(false, msg);
        } catch (Exception e) {
            String msg = "Error executing action " + actionName + ": " + e.getMessage();
            logger.log(Level.SEVERE, msg, e);
            return new ActionResult(false, msg);
        }
    }

    /**
     * Shuts down the scheduler and cancels all scheduled tasks.
     *
     * <p>This method will attempt to gracefully shutdown the scheduler executor,
     * waiting up to 5 seconds for tasks to terminate. If tasks do not complete
     * in time, a forceful shutdown will be attempted.</p>
     */
    @Override
    public void close() {
        logger.log(Level.INFO, "Shutting down scheduler, cancelling " +
            scheduledTasks.size() + " scheduled tasks");

        // Cancel all scheduled tasks
        scheduledTasks.values().forEach(task -> task.cancel(false));
        scheduledTasks.clear();

        // Shutdown the executor
        schedulerExecutor.shutdown();
        try {
            if (!schedulerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.log(Level.WARNING, "Scheduler did not terminate within 5 seconds, forcing shutdown");
                schedulerExecutor.shutdownNow();
                if (!schedulerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.log(Level.SEVERE, "Scheduler did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, "Interrupted while waiting for scheduler termination", e);
            schedulerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
