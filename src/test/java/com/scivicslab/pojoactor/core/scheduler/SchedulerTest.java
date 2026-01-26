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

package com.scivicslab.pojoactor.core.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * Tests for Scheduler (ActorRef-based) functionality.
 *
 * <p>This test suite verifies the base scheduling capabilities of the POJO-actor framework,
 * including fixed-rate execution, fixed-delay execution, and one-time scheduled tasks
 * using lambda-based actions on ActorRef.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.11.0
 */
@DisplayName("Scheduler (ActorRef-based) Specification by Example")
public class SchedulerTest {

    private ActorSystem system;
    private Scheduler scheduler;

    @BeforeEach
    public void setUp() {
        system = new ActorSystem("scheduler-test-system", 2);
        scheduler = new Scheduler();
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
    private static class CounterActor {
        private final AtomicInteger count = new AtomicInteger(0);

        public void increment() {
            count.incrementAndGet();
        }

        public int getCount() {
            return count.get();
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
        ActorRef<CounterActor> counterRef = system.actorOf("counter", counter);

        String taskId = scheduler.scheduleAtFixedRate(
            "test-task", counterRef, CounterActor::increment,
            0, 100, TimeUnit.MILLISECONDS
        );

        assertEquals("test-task", taskId, "Should return task ID");
        assertEquals(1, scheduler.getScheduledTaskCount(), "Should have 1 scheduled task");
        assertTrue(scheduler.isScheduled("test-task"), "Task should be scheduled");

        Thread.sleep(350);

        int count = counterRef.ask(CounterActor::getCount).join();
        assertTrue(count >= 3,
            "Counter should be incremented at least 3 times, but was: " + count);

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
        ActorRef<CounterActor> counterRef = system.actorOf("counter", counter);

        String taskId = scheduler.scheduleWithFixedDelay(
            "delay-task", counterRef, CounterActor::increment,
            0, 100, TimeUnit.MILLISECONDS
        );

        assertEquals("delay-task", taskId);
        assertTrue(scheduler.isScheduled("delay-task"));

        Thread.sleep(350);

        int count = counterRef.ask(CounterActor::getCount).join();
        assertTrue(count >= 3,
            "Counter should be incremented at least 3 times, but was: " + count);

        scheduler.cancelTask("delay-task");
    }

    /**
     * Example 4: Schedule one-time task.
     */
    @Test
    @DisplayName("Should schedule one-time task")
    public void testScheduleOnce() throws InterruptedException {
        CounterActor counter = new CounterActor();
        ActorRef<CounterActor> counterRef = system.actorOf("counter", counter);

        scheduler.scheduleOnce(
            "once-task", counterRef, CounterActor::increment,
            100, TimeUnit.MILLISECONDS
        );

        assertTrue(scheduler.isScheduled("once-task"));

        Thread.sleep(50);
        assertEquals(0, counterRef.ask(CounterActor::getCount).join(), "Should not execute yet");

        Thread.sleep(100);
        assertEquals(1, counterRef.ask(CounterActor::getCount).join(), "Should execute exactly once");

        Thread.sleep(200);
        assertEquals(1, counterRef.ask(CounterActor::getCount).join(), "Should still be 1 (not repeated)");

        assertFalse(scheduler.isScheduled("once-task"), "Task should be removed after execution");
    }

    /**
     * Example 5: Cancel scheduled task.
     */
    @Test
    @DisplayName("Should cancel scheduled task")
    public void testCancelTask() throws InterruptedException {
        CounterActor counter = new CounterActor();
        ActorRef<CounterActor> counterRef = system.actorOf("counter", counter);

        scheduler.scheduleAtFixedRate(
            "cancel-test", counterRef, CounterActor::increment,
            0, 100, TimeUnit.MILLISECONDS
        );

        Thread.sleep(250);
        int countBeforeCancel = counterRef.ask(CounterActor::getCount).join();
        assertTrue(countBeforeCancel >= 2, "Should have executed at least twice");

        boolean cancelled = scheduler.cancelTask("cancel-test");
        assertTrue(cancelled, "Should confirm cancellation");
        assertFalse(scheduler.isScheduled("cancel-test"), "Task should not be scheduled");

        Thread.sleep(300);
        assertEquals(countBeforeCancel, counterRef.ask(CounterActor::getCount).join(),
            "Count should not increase after cancellation");
    }

    /**
     * Example 6: Multiple scheduled tasks.
     */
    @Test
    @DisplayName("Should manage multiple scheduled tasks")
    public void testMultipleTasks() throws InterruptedException {
        CounterActor counter1 = new CounterActor();
        CounterActor counter2 = new CounterActor();
        ActorRef<CounterActor> counter1Ref = system.actorOf("counter1", counter1);
        ActorRef<CounterActor> counter2Ref = system.actorOf("counter2", counter2);

        scheduler.scheduleAtFixedRate("task1", counter1Ref, CounterActor::increment, 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("task2", counter2Ref, CounterActor::increment, 0, 150, TimeUnit.MILLISECONDS);

        assertEquals(2, scheduler.getScheduledTaskCount(), "Should have 2 scheduled tasks");
        assertTrue(scheduler.isScheduled("task1"));
        assertTrue(scheduler.isScheduled("task2"));

        Thread.sleep(400);

        assertTrue(counter1Ref.ask(CounterActor::getCount).join() >= 3, "Counter1 should increment more");
        assertTrue(counter2Ref.ask(CounterActor::getCount).join() >= 2, "Counter2 should increment less");

        scheduler.cancelTask("task1");
        assertEquals(1, scheduler.getScheduledTaskCount(), "Should have 1 task remaining");
        assertTrue(scheduler.isScheduled("task2"));
        assertFalse(scheduler.isScheduled("task1"));

        scheduler.cancelTask("task2");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should have no tasks");
    }

    /**
     * Example 7: Replace existing task.
     */
    @Test
    @DisplayName("Should replace existing task with same ID")
    public void testReplaceTask() throws InterruptedException {
        CounterActor counter = new CounterActor();
        ActorRef<CounterActor> counterRef = system.actorOf("counter", counter);

        scheduler.scheduleAtFixedRate("replace-test", counterRef, CounterActor::increment, 0, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(250);
        int count1 = counterRef.ask(CounterActor::getCount).join();
        assertTrue(count1 >= 2);

        // Replace with faster task
        scheduler.scheduleAtFixedRate("replace-test", counterRef, CounterActor::increment, 0, 50, TimeUnit.MILLISECONDS);

        assertEquals(1, scheduler.getScheduledTaskCount());

        Thread.sleep(250);
        int count2 = counterRef.ask(CounterActor::getCount).join();
        assertTrue(count2 > count1, "New task should run faster than old task");
    }

    /**
     * Example 8: Close scheduler cleans up all tasks.
     */
    @Test
    @DisplayName("Should cleanup all tasks on close")
    public void testCloseCleanup() {
        CounterActor counter = new CounterActor();
        ActorRef<CounterActor> counterRef = system.actorOf("counter", counter);

        scheduler.scheduleAtFixedRate("t1", counterRef, CounterActor::increment, 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("t2", counterRef, CounterActor::increment, 0, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("t3", counterRef, CounterActor::increment, 0, 100, TimeUnit.MILLISECONDS);

        assertEquals(3, scheduler.getScheduledTaskCount());

        scheduler.close();

        assertEquals(0, scheduler.getScheduledTaskCount());
        assertFalse(scheduler.isScheduled("t1"));
        assertFalse(scheduler.isScheduled("t2"));
        assertFalse(scheduler.isScheduled("t3"));
    }

    /**
     * Example 9: Cancel non-existent task.
     */
    @Test
    @DisplayName("Should handle cancellation of non-existent task")
    public void testCancelNonExistentTask() {
        boolean result = scheduler.cancelTask("non-existent-task");

        assertFalse(result, "Should return false for non-existent task");
        assertEquals(0, scheduler.getScheduledTaskCount(), "Should have no tasks");
    }

    /**
     * Example 10: Scheduler with custom pool size.
     */
    @Test
    @DisplayName("Should support custom thread pool size")
    public void testCustomPoolSize() {
        Scheduler customScheduler = new Scheduler(4);
        assertNotNull(customScheduler);
        assertEquals(0, customScheduler.getScheduledTaskCount());
        customScheduler.close();
    }
}
