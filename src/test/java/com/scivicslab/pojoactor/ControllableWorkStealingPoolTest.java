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
package com.scivicslab.pojoactor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test class for verifying ControllableWorkStealingPool functionality.
 *
 * This test verifies that:
 * 1. Jobs can be cancelled from the pool queue
 * 2. clearPendingMessages() cancels both message queue and WorkStealingPool jobs
 * 3. CPU is not consumed by cancelled jobs
 *
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("ControllableWorkStealingPool tests")
public class ControllableWorkStealingPoolTest {

    private static final Logger logger = Logger.getLogger(ControllableWorkStealingPoolTest.class.getName());
    private ActorSystem system;

    @BeforeEach
    public void setUp() {
        system = new ActorSystem("testSystem", 4);
    }

    @AfterEach
    public void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    /**
     * Test that clearPendingMessages() actually cancels WorkStealingPool jobs.
     *
     * Expected: After calling clearPendingMessages(), most jobs should NOT execute.
     */
    @DisplayName("Should cancel WorkStealingPool jobs via clearPendingMessages()")
    @Test
    public void testClearPendingMessagesWithWorkStealingPool() throws InterruptedException {
        AtomicInteger started = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Submit 100 CPU-bound tasks to WorkStealingPool
        for (int i = 0; i < 100; i++) {
            final int taskId = i;
            actor.tell(s -> {
                started.incrementAndGet();
                // Simulate CPU-bound work (~100ms)
                long endTime = System.currentTimeMillis() + 100;
                while (System.currentTimeMillis() < endTime) {
                    Math.sqrt(Math.random());
                }
                completed.incrementAndGet();
                logger.info("Task " + taskId + " completed");
            }, system.getWorkStealingPool());
        }

        // Wait 500ms to let some tasks start
        Thread.sleep(500);

        int startedBefore = started.get();
        int completedBefore = completed.get();
        logger.info("Before clearPendingMessages: started=" + startedBefore + ", completed=" + completedBefore);

        // Clear all pending messages (should cancel WorkStealingPool jobs)
        int cleared = actor.clearPendingMessages();
        logger.info("Cleared " + cleared + " messages/jobs");

        // Wait to see if cancelled tasks still execute
        Thread.sleep(1000);

        int finalStarted = started.get();
        int finalCompleted = completed.get();

        logger.info("=== TEST RESULT ===");
        logger.info("Started: " + finalStarted + " / 100");
        logger.info("Completed: " + finalCompleted + " / 100");
        logger.info("Cleared: " + cleared);
        logger.info("Tasks started after clear: " + (finalStarted - startedBefore));
        logger.info("Tasks completed after clear: " + (finalCompleted - completedBefore));

        // Verify that clearPendingMessages() worked
        assertTrue(cleared > 0, "Should have cleared some jobs");

        // Most tasks should NOT execute (only ~20 should complete in 500ms with 4 threads)
        assertTrue(finalCompleted < 40,
            "Most tasks should be cancelled (expected <40, got " + finalCompleted + ")");

        // Very few (or zero) tasks should start after clear
        int startedAfterClear = finalStarted - startedBefore;
        assertTrue(startedAfterClear < 10,
            "Very few tasks should start after clear (expected <10, got " + startedAfterClear + ")");
    }

    /**
     * Test ControllableWorkStealingPool's actor-level job tracking.
     *
     * Expected: Jobs from different actors are tracked separately.
     */
    @DisplayName("Should track jobs per actor independently")
    @Test
    public void testPerActorJobTracking() throws InterruptedException {
        AtomicInteger actor1Completed = new AtomicInteger(0);
        AtomicInteger actor2Completed = new AtomicInteger(0);

        ActorRef<String> actor1 = system.actorOf("actor1", "test1");
        ActorRef<String> actor2 = system.actorOf("actor2", "test2");

        // Submit jobs from both actors
        for (int i = 0; i < 50; i++) {
            actor1.tell(s -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                actor1Completed.incrementAndGet();
            }, system.getWorkStealingPool());

            actor2.tell(s -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                actor2Completed.incrementAndGet();
            }, system.getWorkStealingPool());
        }

        // Wait a bit
        Thread.sleep(300);

        // Clear only actor1's messages
        int cleared1 = actor1.clearPendingMessages();
        logger.info("Cleared " + cleared1 + " jobs from actor1");

        // Wait for remaining jobs
        Thread.sleep(2000);

        int final1 = actor1Completed.get();
        int final2 = actor2Completed.get();

        logger.info("Actor1 completed: " + final1 + " / 50");
        logger.info("Actor2 completed: " + final2 + " / 50");

        // Actor1 should have fewer completions (many were cancelled)
        assertTrue(final1 < 25, "Actor1 should have most jobs cancelled (got " + final1 + ")");

        // Actor2 should complete more jobs (not cancelled)
        assertTrue(final2 > final1, "Actor2 should complete more jobs than actor1");
    }

    /**
     * Test that clearPendingMessages() clears both message queue and WorkStealingPool.
     *
     * Expected: Return value should be the sum of cleared messages and jobs.
     */
    @DisplayName("Should clear both message queue and WorkStealingPool jobs")
    @Test
    public void testClearBothMessageQueueAndPool() throws InterruptedException {
        ActorRef<AtomicInteger> actor = system.actorOf("counter", new AtomicInteger(0));

        // Add messages to message queue (lightweight)
        for (int i = 0; i < 10; i++) {
            actor.tell(c -> c.incrementAndGet());
        }

        // Add jobs to WorkStealingPool (CPU-bound)
        for (int i = 0; i < 20; i++) {
            actor.tell(c -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                c.incrementAndGet();
            }, system.getWorkStealingPool());
        }

        // Give time for some to start
        Thread.sleep(100);

        // Clear all
        int cleared = actor.clearPendingMessages();

        logger.info("Total cleared: " + cleared);

        // Should clear from both sources (some lightweight messages may execute quickly)
        assertTrue(cleared >= 10, "Should clear at least 10 items (got " + cleared + ")");
    }
}
