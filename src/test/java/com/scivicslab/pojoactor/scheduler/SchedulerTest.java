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

package com.scivicslab.pojoactor.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.ActionResult;
import com.scivicslab.pojoactor.CallableByActionName;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Comprehensive tests for Scheduler functionality.
 *
 * <p>This test suite verifies the scheduling capabilities of the POJO-actor framework,
 * including fixed-rate execution, fixed-delay execution, and one-time scheduled tasks.</p>
 *
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("Scheduler Specification by Example")
public class SchedulerTest {

    private IIActorSystem system;
    private Scheduler scheduler;
    private SchedulerIIAR schedulerRef;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("scheduler-test-system");
        scheduler = new Scheduler(system);
        schedulerRef = new SchedulerIIAR("scheduler", scheduler, system);
        system.addIIActor(schedulerRef);
    }

    @AfterEach
    public void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
        if (system != null) {
            system.terminate();
        }
    }

    /**
     * Test actor that counts invocations.
     */
    private static class CounterActor implements CallableByActionName {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if (actionName.equals("increment")) {
                int newCount = count.incrementAndGet();
                return new ActionResult(true, "Count: " + newCount);
            }
            return new ActionResult(false, "Unknown action");
        }

        public int getCount() {
            return count.get();
        }
    }

    /**
     * IIActorRef wrapper for CounterActor.
     */
    private static class CounterActorIIAR extends IIActorRef<CounterActor> {
        public CounterActorIIAR(String actorName, CounterActor object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return this.object.callByActionName(actionName, args);
        }
    }

    /**
     * Example 1: Create and use scheduler.
     */
    @Test
    @DisplayName("Should create scheduler successfully")
    public void testCreateScheduler() {
        assertNotNull(scheduler, "Scheduler should be created");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should have no tasks initially");
    }

    /**
     * Example 2: Schedule task at fixed rate.
     */
    @Test
    @DisplayName("Should schedule task at fixed rate")
    public void testScheduleAtFixedRate() throws InterruptedException {
        // Setup counter actor
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Schedule task every 100ms
        String result = scheduler.scheduleAtFixedRate(
            "test-task", "counter", "increment", "",
            0, 100, TimeUnit.MILLISECONDS
        );

        assertTrue(result.contains("Scheduled at fixed rate"), "Should confirm scheduling");
        assertEquals(1, scheduler.getScheduledTaskCount(), "Should have 1 scheduled task");
        assertTrue(scheduler.isScheduled("test-task"), "Task should be scheduled");

        // Wait for task to execute multiple times
        Thread.sleep(350);

        // Should have executed at least 3 times (0ms, 100ms, 200ms, 300ms)
        assertTrue(counter.getCount() >= 3,
            "Counter should be incremented at least 3 times, but was: " + counter.getCount());

        // Cancel task
        scheduler.cancelTask("test-task");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should have no tasks after cancellation");
    }

    /**
     * Example 3: Schedule task with fixed delay.
     */
    @Test
    @DisplayName("Should schedule task with fixed delay")
    public void testScheduleWithFixedDelay() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Schedule with 100ms delay between executions
        String result = scheduler.scheduleWithFixedDelay(
            "delay-task", "counter", "increment", "",
            0, 100, TimeUnit.MILLISECONDS
        );

        assertTrue(result.contains("Scheduled with fixed delay"));
        assertTrue(scheduler.isScheduled("delay-task"));

        Thread.sleep(350);

        // Should execute multiple times
        assertTrue(counter.getCount() >= 3,
            "Counter should be incremented at least 3 times, but was: " + counter.getCount());

        scheduler.cancelTask("delay-task");
    }

    /**
     * Example 4: Schedule one-time task.
     */
    @Test
    @DisplayName("Should schedule one-time task")
    public void testScheduleOnce() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Schedule task to run once after 100ms
        String result = scheduler.scheduleOnce(
            "once-task", "counter", "increment", "",
            100, TimeUnit.MILLISECONDS
        );

        assertTrue(result.contains("Scheduled once"));
        assertTrue(scheduler.isScheduled("once-task"));

        // Wait before execution
        Thread.sleep(50);
        assertEquals(0, counter.getCount(), "Should not execute yet");

        // Wait for execution
        Thread.sleep(100);
        assertEquals(1, counter.getCount(), "Should execute exactly once");

        // Wait more to ensure it doesn't run again
        Thread.sleep(200);
        assertEquals(1, counter.getCount(), "Should still be 1 (not repeated)");

        // Task should be auto-removed
        assertFalse(scheduler.isScheduled("once-task"), "Task should be removed after execution");
    }

    /**
     * Example 5: Cancel scheduled task.
     */
    @Test
    @DisplayName("Should cancel scheduled task")
    public void testCancelTask() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        scheduler.scheduleAtFixedRate(
            "cancel-test", "counter", "increment", "",
            0, 100, TimeUnit.MILLISECONDS
        );

        // Wait for some executions
        Thread.sleep(250);
        int countBeforeCancel = counter.getCount();
        assertTrue(countBeforeCancel >= 2, "Should have executed at least twice");

        // Cancel the task
        String cancelResult = scheduler.cancelTask("cancel-test");
        assertTrue(cancelResult.contains("Cancelled"), "Should confirm cancellation");
        assertFalse(scheduler.isScheduled("cancel-test"), "Task should not be scheduled");

        // Wait more and verify count doesn't increase
        Thread.sleep(300);
        assertEquals(countBeforeCancel, counter.getCount(),
            "Count should not increase after cancellation");
    }

    /**
     * Example 6: Handle non-existent actor gracefully.
     */
    @Test
    @DisplayName("Should handle non-existent actor gracefully")
    public void testNonExistentActor() {
        String result = scheduler.scheduleAtFixedRate(
            "bad-task", "nonexistent-actor", "action", "",
            0, 100, TimeUnit.MILLISECONDS
        );

        assertTrue(result.contains("Actor not found"), "Should report actor not found");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should not schedule task");
    }

    /**
     * Example 7: Multiple scheduled tasks.
     */
    @Test
    @DisplayName("Should manage multiple scheduled tasks")
    public void testMultipleTasks() throws InterruptedException {
        CounterActor counter1 = new CounterActor();
        CounterActor counter2 = new CounterActor();
        CounterActorIIAR counter1Ref = new CounterActorIIAR("counter1", counter1, system);
        CounterActorIIAR counter2Ref = new CounterActorIIAR("counter2", counter2, system);
        system.addIIActor(counter1Ref);
        system.addIIActor(counter2Ref);

        // Schedule two tasks
        scheduler.scheduleAtFixedRate("task1", "counter1", "increment", "", 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("task2", "counter2", "increment", "", 0, 150, TimeUnit.MILLISECONDS);

        assertEquals(2, scheduler.getScheduledTaskCount(), "Should have 2 scheduled tasks");
        assertTrue(scheduler.isScheduled("task1"));
        assertTrue(scheduler.isScheduled("task2"));

        Thread.sleep(400);

        // task1 runs faster than task2
        assertTrue(counter1.getCount() >= 3, "Counter1 should increment more");
        assertTrue(counter2.getCount() >= 2, "Counter2 should increment less");

        // Cancel one task
        scheduler.cancelTask("task1");
        assertEquals(1, scheduler.getScheduledTaskCount(), "Should have 1 task remaining");
        assertTrue(scheduler.isScheduled("task2"));
        assertFalse(scheduler.isScheduled("task1"));

        // Cancel remaining task
        scheduler.cancelTask("task2");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should have no tasks");
    }

    /**
     * Example 8: Use SchedulerIIAR for action-based invocation.
     */
    @Test
    @DisplayName("Should work through SchedulerIIAR")
    public void testSchedulerIIAR() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Use callByActionName through SchedulerIIAR
        ActionResult result = schedulerRef.callByActionName("scheduleAtFixedRate",
            "iiar-task,counter,increment,,0,100,MILLISECONDS");

        assertTrue(result.isSuccess(), "Should schedule successfully");
        assertTrue(result.getResult().contains("Scheduled at fixed rate"));

        Thread.sleep(350);
        assertTrue(counter.getCount() >= 3, "Should execute through IIAR");

        // Cancel through IIAR
        ActionResult cancelResult = schedulerRef.callByActionName("cancel", "iiar-task");
        assertTrue(cancelResult.isSuccess());
        assertTrue(cancelResult.getResult().contains("Cancelled"));
    }

    /**
     * Example 9: Get task count through action.
     */
    @Test
    @DisplayName("Should get task count through action")
    public void testGetTaskCount() {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Initially 0
        ActionResult result = schedulerRef.callByActionName("getTaskCount", "");
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().contains("0"));

        // Schedule 2 tasks
        scheduler.scheduleAtFixedRate("t1", "counter", "increment", "", 0, 1000, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("t2", "counter", "increment", "", 0, 1000, TimeUnit.MILLISECONDS);

        result = schedulerRef.callByActionName("getTaskCount", "");
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().contains("2"));

        // Cancel 1 task
        scheduler.cancelTask("t1");

        result = schedulerRef.callByActionName("getTaskCount", "");
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().contains("1"));
    }

    /**
     * Example 10: Check if task is scheduled through action.
     */
    @Test
    @DisplayName("Should check if task is scheduled through action")
    public void testIsScheduled() {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Task doesn't exist yet
        ActionResult result = schedulerRef.callByActionName("isScheduled", "check-task");
        assertTrue(result.isSuccess());
        assertEquals("false", result.getResult());

        // Schedule task
        scheduler.scheduleAtFixedRate("check-task", "counter", "increment", "", 0, 1000, TimeUnit.MILLISECONDS);

        result = schedulerRef.callByActionName("isScheduled", "check-task");
        assertTrue(result.isSuccess());
        assertEquals("true", result.getResult());

        // Cancel task
        scheduler.cancelTask("check-task");

        result = schedulerRef.callByActionName("isScheduled", "check-task");
        assertTrue(result.isSuccess());
        assertEquals("false", result.getResult());
    }

    /**
     * Example 11: Replace existing task.
     */
    @Test
    @DisplayName("Should replace existing task with same ID")
    public void testReplaceTask() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Schedule first task
        scheduler.scheduleAtFixedRate("replace-test", "counter", "increment", "", 0, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(250);
        int count1 = counter.getCount();
        assertTrue(count1 >= 2);

        // Schedule again with same ID (should replace)
        scheduler.scheduleAtFixedRate("replace-test", "counter", "increment", "", 0, 50, TimeUnit.MILLISECONDS);

        // Still only 1 task
        assertEquals(1, scheduler.getScheduledTaskCount());

        // New task runs faster
        Thread.sleep(250);
        int count2 = counter.getCount();
        assertTrue(count2 > count1, "New task should run faster than old task");
    }

    /**
     * Example 12: Close scheduler cleans up all tasks.
     */
    @Test
    @DisplayName("Should cleanup all tasks on close")
    public void testCloseCleanup() {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Schedule multiple tasks
        scheduler.scheduleAtFixedRate("t1", "counter", "increment", "", 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("t2", "counter", "increment", "", 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("t3", "counter", "increment", "", 0, 100, TimeUnit.MILLISECONDS);

        assertEquals(3, scheduler.getScheduledTaskCount());

        // Close scheduler
        scheduler.close();

        // All tasks should be cancelled
        assertEquals(0, scheduler.getScheduledTaskCount());
        assertFalse(scheduler.isScheduled("t1"));
        assertFalse(scheduler.isScheduled("t2"));
        assertFalse(scheduler.isScheduled("t3"));
    }

    /**
     * Example 13: Use scheduleWithFixedDelay through callByActionName.
     */
    @Test
    @DisplayName("Should schedule with fixed delay through callByActionName")
    public void testScheduleWithFixedDelayThroughAction() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Use callByActionName to schedule with fixed delay
        ActionResult result = schedulerRef.callByActionName("scheduleWithFixedDelay",
            "delay-task,counter,increment,,0,100,MILLISECONDS");

        assertTrue(result.isSuccess(), "Should schedule successfully");
        assertTrue(result.getResult().contains("Scheduled with fixed delay"));

        Thread.sleep(350);
        assertTrue(counter.getCount() >= 3, "Should execute multiple times");

        scheduler.cancelTask("delay-task");
    }

    /**
     * Example 14: Use scheduleOnce through callByActionName.
     */
    @Test
    @DisplayName("Should schedule once through callByActionName")
    public void testScheduleOnceThroughAction() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        // Use callByActionName to schedule once
        ActionResult result = schedulerRef.callByActionName("scheduleOnce",
            "once-task,counter,increment,,100,MILLISECONDS");

        assertTrue(result.isSuccess(), "Should schedule successfully");
        assertTrue(result.getResult().contains("Scheduled once"));

        Thread.sleep(50);
        assertEquals(0, counter.getCount(), "Should not execute yet");

        Thread.sleep(100);
        assertEquals(1, counter.getCount(), "Should execute exactly once");
    }

    /**
     * Example 15: Handle unknown action in callByActionName.
     */
    @Test
    @DisplayName("Should handle unknown action gracefully")
    public void testUnknownAction() {
        ActionResult result = schedulerRef.callByActionName("unknownAction", "some,args");

        assertFalse(result.isSuccess(), "Unknown action should fail");
        assertTrue(result.getResult().contains("Unknown action"), "Should report unknown action");
    }

    /**
     * Example 16: Handle invalid arguments for scheduleAtFixedRate.
     */
    @Test
    @DisplayName("Should handle invalid arguments for scheduleAtFixedRate")
    public void testInvalidArgumentsForFixedRate() {
        // Only 6 arguments instead of required 7
        ActionResult result = schedulerRef.callByActionName("scheduleAtFixedRate",
            "task,counter,action,args,0,100");

        assertFalse(result.isSuccess(), "Should fail with invalid arguments");
        assertTrue(result.getResult().contains("Invalid arguments"), "Should report invalid arguments");
    }

    /**
     * Example 17: Handle invalid arguments for scheduleWithFixedDelay.
     */
    @Test
    @DisplayName("Should handle invalid arguments for scheduleWithFixedDelay")
    public void testInvalidArgumentsForFixedDelay() {
        // Only 6 arguments instead of required 7
        ActionResult result = schedulerRef.callByActionName("scheduleWithFixedDelay",
            "task,counter,action,args,0,100");

        assertFalse(result.isSuccess(), "Should fail with invalid arguments");
        assertTrue(result.getResult().contains("Invalid arguments"), "Should report invalid arguments");
    }

    /**
     * Example 18: Handle invalid arguments for scheduleOnce.
     */
    @Test
    @DisplayName("Should handle invalid arguments for scheduleOnce")
    public void testInvalidArgumentsForOnce() {
        // Only 5 arguments instead of required 6
        ActionResult result = schedulerRef.callByActionName("scheduleOnce",
            "task,counter,action,args,100");

        assertFalse(result.isSuccess(), "Should fail with invalid arguments");
        assertTrue(result.getResult().contains("Invalid arguments"), "Should report invalid arguments");
    }

    /**
     * Example 19: Handle number format error in callByActionName.
     */
    @Test
    @DisplayName("Should handle number format error")
    public void testNumberFormatError() {
        // Invalid number format for delay
        ActionResult result = schedulerRef.callByActionName("scheduleAtFixedRate",
            "task,counter,action,args,abc,100,SECONDS");

        assertFalse(result.isSuccess(), "Should fail with number format error");
        assertTrue(result.getResult().contains("Invalid number format"), "Should report number format error");
    }

    /**
     * Example 20: Handle invalid TimeUnit value.
     */
    @Test
    @DisplayName("Should handle invalid TimeUnit value")
    public void testInvalidTimeUnit() {
        // Invalid TimeUnit value
        ActionResult result = schedulerRef.callByActionName("scheduleAtFixedRate",
            "task,counter,action,args,0,100,INVALID_UNIT");

        assertFalse(result.isSuccess(), "Should fail with invalid TimeUnit");
        assertTrue(result.getResult().contains("Invalid argument") ||
                   result.getResult().contains("Error executing action"),
                   "Should report invalid argument or execution error");
    }

    /**
     * Example 21: Cancel non-existent task.
     */
    @Test
    @DisplayName("Should handle cancellation of non-existent task")
    public void testCancelNonExistentTask() {
        String result = scheduler.cancelTask("non-existent-task");

        assertTrue(result.contains("Task not found"), "Should report task not found");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should have no tasks");
    }
}
