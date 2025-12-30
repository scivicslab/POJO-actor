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

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Tests for Scheduler (core) functionality.
 *
 * <p>This test suite verifies the base scheduling capabilities of the POJO-actor framework,
 * including fixed-rate execution, fixed-delay execution, and one-time scheduled tasks.</p>
 *
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("Scheduler (Core) Specification by Example")
public class SchedulerTest {

    private IIActorSystem system;
    private Scheduler scheduler;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("scheduler-test-system");
        scheduler = new Scheduler(system);
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
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        String result = scheduler.scheduleAtFixedRate(
            "test-task", "counter", "increment", "",
            0, 100, TimeUnit.MILLISECONDS
        );

        assertTrue(result.contains("Scheduled at fixed rate"), "Should confirm scheduling");
        assertEquals(1, scheduler.getScheduledTaskCount(), "Should have 1 scheduled task");
        assertTrue(scheduler.isScheduled("test-task"), "Task should be scheduled");

        Thread.sleep(350);

        assertTrue(counter.getCount() >= 3,
            "Counter should be incremented at least 3 times, but was: " + counter.getCount());

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

        String result = scheduler.scheduleWithFixedDelay(
            "delay-task", "counter", "increment", "",
            0, 100, TimeUnit.MILLISECONDS
        );

        assertTrue(result.contains("Scheduled with fixed delay"));
        assertTrue(scheduler.isScheduled("delay-task"));

        Thread.sleep(350);

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

        String result = scheduler.scheduleOnce(
            "once-task", "counter", "increment", "",
            100, TimeUnit.MILLISECONDS
        );

        assertTrue(result.contains("Scheduled once"));
        assertTrue(scheduler.isScheduled("once-task"));

        Thread.sleep(50);
        assertEquals(0, counter.getCount(), "Should not execute yet");

        Thread.sleep(100);
        assertEquals(1, counter.getCount(), "Should execute exactly once");

        Thread.sleep(200);
        assertEquals(1, counter.getCount(), "Should still be 1 (not repeated)");

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

        Thread.sleep(250);
        int countBeforeCancel = counter.getCount();
        assertTrue(countBeforeCancel >= 2, "Should have executed at least twice");

        String cancelResult = scheduler.cancelTask("cancel-test");
        assertTrue(cancelResult.contains("Cancelled"), "Should confirm cancellation");
        assertFalse(scheduler.isScheduled("cancel-test"), "Task should not be scheduled");

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

        scheduler.scheduleAtFixedRate("task1", "counter1", "increment", "", 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("task2", "counter2", "increment", "", 0, 150, TimeUnit.MILLISECONDS);

        assertEquals(2, scheduler.getScheduledTaskCount(), "Should have 2 scheduled tasks");
        assertTrue(scheduler.isScheduled("task1"));
        assertTrue(scheduler.isScheduled("task2"));

        Thread.sleep(400);

        assertTrue(counter1.getCount() >= 3, "Counter1 should increment more");
        assertTrue(counter2.getCount() >= 2, "Counter2 should increment less");

        scheduler.cancelTask("task1");
        assertEquals(1, scheduler.getScheduledTaskCount(), "Should have 1 task remaining");
        assertTrue(scheduler.isScheduled("task2"));
        assertFalse(scheduler.isScheduled("task1"));

        scheduler.cancelTask("task2");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should have no tasks");
    }

    /**
     * Example 8: Replace existing task.
     */
    @Test
    @DisplayName("Should replace existing task with same ID")
    public void testReplaceTask() throws InterruptedException {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        scheduler.scheduleAtFixedRate("replace-test", "counter", "increment", "", 0, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(250);
        int count1 = counter.getCount();
        assertTrue(count1 >= 2);

        scheduler.scheduleAtFixedRate("replace-test", "counter", "increment", "", 0, 50, TimeUnit.MILLISECONDS);

        assertEquals(1, scheduler.getScheduledTaskCount());

        Thread.sleep(250);
        int count2 = counter.getCount();
        assertTrue(count2 > count1, "New task should run faster than old task");
    }

    /**
     * Example 9: Close scheduler cleans up all tasks.
     */
    @Test
    @DisplayName("Should cleanup all tasks on close")
    public void testCloseCleanup() {
        CounterActor counter = new CounterActor();
        CounterActorIIAR counterRef = new CounterActorIIAR("counter", counter, system);
        system.addIIActor(counterRef);

        scheduler.scheduleAtFixedRate("t1", "counter", "increment", "", 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("t2", "counter", "increment", "", 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("t3", "counter", "increment", "", 0, 100, TimeUnit.MILLISECONDS);

        assertEquals(3, scheduler.getScheduledTaskCount());

        scheduler.close();

        assertEquals(0, scheduler.getScheduledTaskCount());
        assertFalse(scheduler.isScheduled("t1"));
        assertFalse(scheduler.isScheduled("t2"));
        assertFalse(scheduler.isScheduled("t3"));
    }

    /**
     * Example 10: Cancel non-existent task.
     */
    @Test
    @DisplayName("Should handle cancellation of non-existent task")
    public void testCancelNonExistentTask() {
        String result = scheduler.cancelTask("non-existent-task");

        assertTrue(result.contains("Task not found"), "Should report task not found");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should have no tasks");
    }
}
