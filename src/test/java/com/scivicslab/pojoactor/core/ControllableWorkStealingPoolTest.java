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

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    // ========================================================================
    // New tests for additional API coverage
    // ========================================================================

    /**
     * Test submitUrgentForActor() - urgent jobs should execute before normal jobs.
     *
     * Expected: Urgent job executes near the front of the queue.
     */
    @DisplayName("Should execute urgent jobs before normal jobs")
    @Test
    public void testSubmitUrgentForActor() throws InterruptedException {
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ControllableWorkStealingPool pool = (ControllableWorkStealingPool) system.getWorkStealingPool();

        // Submit 50 normal jobs (each takes ~50ms)
        for (int i = 0; i < 50; i++) {
            final int id = i;
            pool.submitForActor("actor", () -> {
                try { Thread.sleep(50); } catch (InterruptedException e) {}
                executionOrder.add(id);
            });
        }

        // Submit urgent job (ID = -1) immediately after
        pool.submitUrgentForActor("actor", () -> {
            executionOrder.add(-1);
        });

        // Wait for some jobs to complete
        Thread.sleep(500);

        List<Integer> snapshot = new ArrayList<>(executionOrder);
        int urgentIndex = snapshot.indexOf(-1);
        logger.info("Urgent job (-1) executed at index: " + urgentIndex);
        logger.info("Execution order (first 10): " + snapshot.subList(0, Math.min(10, snapshot.size())));

        // Urgent job should execute near the front (within first 10 positions)
        assertTrue(urgentIndex >= 0, "Urgent job should have executed");
        assertTrue(urgentIndex < 10, "Urgent job should execute near the front (got index " + urgentIndex + ")");
    }

    /**
     * Test getPendingJobCountForActor() - should return correct pending count.
     *
     * Expected: Count decreases as jobs complete.
     */
    @DisplayName("Should return correct pending job count per actor")
    @Test
    public void testGetPendingJobCountForActor() throws InterruptedException {
        ControllableWorkStealingPool pool = (ControllableWorkStealingPool) system.getWorkStealingPool();

        // Submit 30 jobs (each takes ~100ms)
        for (int i = 0; i < 30; i++) {
            pool.submitForActor("testActor", () -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            });
        }

        // Check pending count immediately
        int pendingInitial = pool.getPendingJobCountForActor("testActor");
        logger.info("Initial pending count: " + pendingInitial);
        assertTrue(pendingInitial > 20, "Should have many pending jobs initially (got " + pendingInitial + ")");

        // Wait and check again
        Thread.sleep(500);
        int pendingAfter = pool.getPendingJobCountForActor("testActor");
        logger.info("Pending count after 500ms: " + pendingAfter);
        assertTrue(pendingAfter < pendingInitial, "Pending count should decrease over time");
    }

    /**
     * Test supportsCancellation() for ControllableWorkStealingPool.
     *
     * Expected: ControllableWorkStealingPool returns true.
     */
    @DisplayName("ControllableWorkStealingPool should support cancellation")
    @Test
    public void testSupportsCancellation() {
        ControllableWorkStealingPool pool = (ControllableWorkStealingPool) system.getWorkStealingPool();
        assertTrue(pool.supportsCancellation(),
            "ControllableWorkStealingPool should support cancellation");
    }

    /**
     * Test supportsCancellation() for ForkJoinPoolWrapper.
     *
     * Expected: ForkJoinPoolWrapper returns false.
     */
    @DisplayName("ForkJoinPoolWrapper should NOT support cancellation")
    @Test
    public void testForkJoinPoolWrapperDoesNotSupportCancellation() {
        ForkJoinPoolWrapper wrapper = new ForkJoinPoolWrapper(4);
        assertFalse(wrapper.supportsCancellation(),
            "ForkJoinPoolWrapper should NOT support cancellation");
        wrapper.shutdown();
    }

    /**
     * Test getTrackedActorCount() - should return correct number of tracked actors.
     *
     * Expected: Count reflects actors with pending/running jobs.
     */
    @DisplayName("Should track correct number of actors")
    @Test
    public void testGetTrackedActorCount() throws InterruptedException {
        ControllableWorkStealingPool pool = new ControllableWorkStealingPool(4);

        // Submit jobs from 3 different actors
        pool.submitForActor("actor1", () -> {
            try { Thread.sleep(200); } catch (InterruptedException e) {}
        });
        pool.submitForActor("actor2", () -> {
            try { Thread.sleep(200); } catch (InterruptedException e) {}
        });
        pool.submitForActor("actor3", () -> {
            try { Thread.sleep(200); } catch (InterruptedException e) {}
        });

        int trackedCount = pool.getTrackedActorCount();
        logger.info("Tracked actor count: " + trackedCount);
        assertEquals(3, trackedCount, "Should track 3 actors");

        pool.shutdown();
    }

    /**
     * Test ForkJoinPoolWrapper cancelJobsForActor() returns 0.
     *
     * Expected: Always returns 0 (cancellation not supported).
     */
    @DisplayName("ForkJoinPoolWrapper cancelJobsForActor should return 0")
    @Test
    public void testForkJoinPoolWrapperCancelReturnsZero() throws InterruptedException {
        ForkJoinPoolWrapper wrapper = new ForkJoinPoolWrapper(4);

        // Submit some jobs
        for (int i = 0; i < 20; i++) {
            wrapper.execute(() -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            });
        }

        // Try to cancel (should return 0)
        int cancelled = wrapper.cancelJobsForActor("anyActor");
        assertEquals(0, cancelled, "ForkJoinPoolWrapper should return 0 for cancel");

        wrapper.shutdown();
    }

    // ========================================================================
    // Edge case tests
    // ========================================================================

    /**
     * Test cancel for non-existent actor.
     *
     * Expected: Returns 0 gracefully.
     */
    @DisplayName("Should handle cancel for non-existent actor")
    @Test
    public void testCancelNonExistentActor() {
        ControllableWorkStealingPool pool = (ControllableWorkStealingPool) system.getWorkStealingPool();
        int cancelled = pool.cancelJobsForActor("nonExistentActor");
        assertEquals(0, cancelled, "Should return 0 for non-existent actor");
    }

    /**
     * Test operations on empty queue.
     *
     * Expected: Returns 0 for all queries.
     */
    @DisplayName("Should handle empty queue gracefully")
    @Test
    public void testEmptyQueueOperations() {
        ControllableWorkStealingPool pool = new ControllableWorkStealingPool(4);
        assertEquals(0, pool.getPendingJobCountForActor("anyActor"),
            "Pending count should be 0 for empty queue");
        assertEquals(0, pool.getTrackedActorCount(),
            "Tracked actor count should be 0 for empty queue");
        pool.shutdown();
    }
}
