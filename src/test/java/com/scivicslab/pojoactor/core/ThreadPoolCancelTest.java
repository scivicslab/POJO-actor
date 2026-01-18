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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test to verify if CompletableFuture.cancel() actually cancels jobs in ManagedThreadPool.
 *
 * This test investigates whether calling cancel() on CompletableFutures removes
 * the actual tasks from the ManagedThreadPool, or if the tasks continue to execute
 * and consume CPU resources.
 *
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("ThreadPool cancel test")
public class ThreadPoolCancelTest {

    private static final Logger logger = Logger.getLogger(ThreadPoolCancelTest.class.getName());
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
     * Test if CompletableFuture.cancel(true) stops CPU-bound jobs in ManagedThreadPool.
     *
     * Test scenario:
     * 1. Submit 100 CPU-bound tasks to ManagedThreadPool (each takes ~100ms)
     * 2. After 500ms, cancel all remaining tasks
     * 3. Count how many tasks actually completed
     *
     * Expected result:
     * - If cancel() works: ~50-60 tasks completed (5 seconds / 100ms * 4 threads)
     * - If cancel() doesn't work: All 100 tasks complete
     */
    @DisplayName("Should cancel CPU-bound jobs in ManagedThreadPool")
    @Test
    public void testCancelManagedThreadPoolJobs() throws InterruptedException {
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger startedCount = new AtomicInteger(0);
        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Submit 100 CPU-bound tasks to ManagedThreadPool
        for (int i = 0; i < 100; i++) {
            final int taskId = i;
            CompletableFuture<Void> future = actor.tell((String s) -> {
                startedCount.incrementAndGet();
                logger.info("Task " + taskId + " started");

                // CPU-bound work (busy loop for ~100ms)
                long endTime = System.currentTimeMillis() + 100;
                while (System.currentTimeMillis() < endTime) {
                    // Check for interruption
                    if (Thread.currentThread().isInterrupted()) {
                        logger.info("Task " + taskId + " interrupted");
                        return;
                    }
                    // Busy work
                    Math.sqrt(Math.random());
                }

                completedCount.incrementAndGet();
                logger.info("Task " + taskId + " completed");
            }, system.getManagedThreadPool());

            // Store the future for cancellation (in real implementation, this would be done in ActorRef)
            // For now, we just let them run
        }

        // Wait 500ms to let some tasks complete
        Thread.sleep(500);

        int completedBefore = completedCount.get();
        int startedBefore = startedCount.get();
        logger.info("After 500ms: started=" + startedBefore + ", completed=" + completedBefore);

        // Now we want to test cancellation
        // In the real implementation, clearPendingMessages() would cancel remaining futures
        // For this test, we'll just wait and see how many complete without cancellation

        // Wait for all tasks to complete (or timeout after 5 seconds)
        Thread.sleep(5000);

        int finalCompleted = completedCount.get();
        int finalStarted = startedCount.get();
        logger.info("After 5.5 seconds: started=" + finalStarted + ", completed=" + finalCompleted);

        // Analysis:
        // - If all 100 tasks completed, it means cancel() doesn't stop execution
        // - If only ~50-60 completed, it means cancel() works

        // For now, just verify that some tasks completed
        assertTrue(completedBefore > 0, "Some tasks should have completed in 500ms");
        assertTrue(completedBefore < 100, "Not all tasks should complete in 500ms");

        logger.info("=== TEST RESULT ===");
        logger.info("This test demonstrates baseline behavior WITHOUT cancellation");
        logger.info("Started: " + finalStarted + " / 100");
        logger.info("Completed: " + finalCompleted + " / 100");
    }

    /**
     * Test WITH cancellation using CompletableFuture.cancel().
     *
     * This test attempts to cancel futures and checks if CPU-bound work actually stops.
     */
    @DisplayName("Should test CompletableFuture.cancel() on CPU-bound jobs")
    @Test
    public void testCompletableFutureCancelOnCpuBound() throws InterruptedException {
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger startedCount = new AtomicInteger(0);
        AtomicInteger interruptedCount = new AtomicInteger(0);
        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Store futures for cancellation
        CompletableFuture<?>[] futures = new CompletableFuture<?>[100];

        // Submit 100 CPU-bound tasks to ManagedThreadPool
        for (int i = 0; i < 100; i++) {
            final int taskId = i;
            futures[i] = actor.tell((String s) -> {
                startedCount.incrementAndGet();
                logger.info("Task " + taskId + " started");

                try {
                    // CPU-bound work (busy loop for ~100ms)
                    long endTime = System.currentTimeMillis() + 100;
                    while (System.currentTimeMillis() < endTime) {
                        // Check for interruption
                        if (Thread.currentThread().isInterrupted()) {
                            interruptedCount.incrementAndGet();
                            logger.info("Task " + taskId + " interrupted");
                            return;
                        }
                        // Busy work
                        Math.sqrt(Math.random());
                    }

                    completedCount.incrementAndGet();
                    logger.info("Task " + taskId + " completed");
                } catch (Exception e) {
                    logger.info("Task " + taskId + " exception: " + e.getMessage());
                }
            }, system.getManagedThreadPool());
        }

        // Wait 500ms to let some tasks start
        Thread.sleep(500);

        int completedBefore = completedCount.get();
        int startedBefore = startedCount.get();
        logger.info("Before cancel: started=" + startedBefore + ", completed=" + completedBefore);

        // Cancel all remaining futures
        int cancelledCount = 0;
        for (CompletableFuture<?> future : futures) {
            if (future.cancel(true)) {
                cancelledCount++;
            }
        }

        logger.info("Cancelled " + cancelledCount + " futures");

        // Wait to see if cancelled tasks still execute
        Thread.sleep(2000);

        int finalCompleted = completedCount.get();
        int finalStarted = startedCount.get();
        int finalInterrupted = interruptedCount.get();

        logger.info("=== TEST RESULT WITH CANCELLATION ===");
        logger.info("Started: " + finalStarted + " / 100");
        logger.info("Completed: " + finalCompleted + " / 100");
        logger.info("Interrupted: " + finalInterrupted + " / 100");
        logger.info("Cancelled futures: " + cancelledCount);
        logger.info("Tasks that started after cancel: " + (finalStarted - startedBefore));
        logger.info("Tasks that completed after cancel: " + (finalCompleted - completedBefore));

        // If cancel() works, tasks started after cancellation should be 0 or very few
        // If cancel() doesn't work, many tasks will start and complete after cancellation

        assertTrue(cancelledCount > 0, "Some futures should be cancelled");

        // Key question: Do cancelled futures prevent execution, or do tasks continue to run?
    }
}
