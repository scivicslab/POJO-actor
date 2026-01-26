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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test class for verifying tellNow() method functionality.
 * The tellNow() method bypasses the actor's mailbox and executes messages immediately
 * on a separate thread, allowing concurrent execution with queued messages.
 *
 * @author devteam@scivicslab.com
 * @version 1.0.0
 */
@DisplayName("TellNow method tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TellNowTest {

    private static final Logger logger = Logger.getLogger(TellNowTest.class.getName());
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
     * Example 1: Skip waiting messages and execute immediately.
     *
     * Situation: An actor has multiple queued messages
     * Expected: tellNow() skips the queue and executes immediately
     */
    @DisplayName("Should skip waiting messages and execute immediately")
    @Test
    @Order(1)
    public void testTellNowExecutesImmediately() throws InterruptedException, ExecutionException, TimeoutException {
        ActorRef<AtomicInteger> actor = system.actorOf("counter", new AtomicInteger(0));
        List<Integer> executionSequence = Collections.synchronizedList(new ArrayList<>());

        // Send 5 normal messages (each sleeps for 100ms)
        for (int i = 0; i < 5; i++) {
            final int value = i;
            actor.tell((AtomicInteger c) -> {
                try {
                    Thread.sleep(100);
                    executionSequence.add(value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Send urgent task via tellNow
        CompletableFuture<Void> urgentTask = actor.tellNow((AtomicInteger c) -> {
            executionSequence.add(999); // 999 as urgent task identifier
        });

        urgentTask.get(1, TimeUnit.SECONDS);

        // Wait for all tell messages to complete
        Thread.sleep(600); // 5 messages * 100ms + buffer

        // Verify urgent task (999) is in the execution sequence
        assertTrue(executionSequence.contains(999), "Urgent task should be executed");

        // Verify urgent task executed before some queued messages completed
        int urgentIndex = executionSequence.indexOf(999);
        assertTrue(urgentIndex < executionSequence.size() - 1,
            "Urgent task should execute before all queued messages are processed");

        // Verify all tasks eventually completed
        assertEquals(6, executionSequence.size(), "All tasks should complete");
    }

    /**
     * Example 2: Run concurrently with executing tell task.
     *
     * Situation: A long-running tell task is executing
     * Expected: tellNow() executes concurrently without waiting for the tell task to complete
     */
    @DisplayName("Should run concurrently with executing tell task")
    @Test
    @Order(2)
    public void testTellNowRunsConcurrentlyWithTell() throws InterruptedException, ExecutionException, TimeoutException {
        AtomicBoolean tellTaskRunning = new AtomicBoolean(false);
        AtomicBoolean tellNowCompleted = new AtomicBoolean(false);

        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Start long-running task
        CompletableFuture<Void> longTask = actor.tell((String s) -> {
            tellTaskRunning.set(true);
            try {
                Thread.sleep(500); // Long-running task
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tellTaskRunning.set(false);
        });

        Thread.sleep(50); // Wait for long task to start

        // Execute urgent task with tellNow
        CompletableFuture<Void> urgentTask = actor.tellNow((String s) -> {
            tellNowCompleted.set(true);
        });

        urgentTask.get(1, TimeUnit.SECONDS);

        // Verify tellNow completed
        assertTrue(tellNowCompleted.get(), "tellNow task should complete");

        // Verify tell task is still running
        assertTrue(tellTaskRunning.get(), "tell task should still be running");

        // Wait for long task to complete
        longTask.get(1, TimeUnit.SECONDS);
    }

    /**
     * Example 3: Exception handling.
     *
     * Situation: Exception occurs within tellNow action
     * Expected: CompletableFuture completes exceptionally
     */
    @DisplayName("Should handle exceptions in tellNow properly")
    @Test
    @Order(3)
    public void testTellNowWithException() {
        ActorRef<String> actor = system.actorOf("testActor", "test");

        CompletableFuture<Void> futureWithException = actor.tellNow((String s) -> {
            throw new RuntimeException("Test exception");
        });

        // Verify that get() throws ExecutionException
        ExecutionException exception = assertThrows(ExecutionException.class, () -> {
            futureWithException.get();
        });

        // Verify the cause is RuntimeException with expected message
        assertTrue(exception.getCause() instanceof RuntimeException,
            "Cause should be RuntimeException");
        assertEquals("Test exception", exception.getCause().getMessage(),
            "Exception message should match");
    }

    /**
     * Example 4: Multiple tellNow calls execute concurrently.
     *
     * Situation: Multiple tellNow calls are made consecutively
     * Expected: Multiple tellNow tasks execute concurrently
     */
    @DisplayName("Should execute multiple tellNow calls concurrently")
    @Test
    @Order(4)
    public void testMultipleTellNowCallsExecuteConcurrently() throws InterruptedException, ExecutionException, TimeoutException {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        ActorRef<String> actor = system.actorOf("testActor", "test");
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Start 5 tellNow tasks
        for (int i = 0; i < 5; i++) {
            CompletableFuture<Void> future = actor.tellNow((String s) -> {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet((int max) -> Math.max(max, current));

                try {
                    Thread.sleep(100); // Simulate work
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                concurrentCount.decrementAndGet();
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(2, TimeUnit.SECONDS);

        // Verify multiple tasks executed concurrently
        assertTrue(maxConcurrent.get() > 1,
            "Multiple tellNow tasks should execute concurrently (maxConcurrent=" + maxConcurrent.get() + ")");
    }

    /**
     * Example 5: Query actor's current state immediately.
     *
     * Situation: A long-running task is executing, and we want to know the actor's current state immediately
     * Expected: askNow() allows querying the state without waiting for the long task to complete
     */
    @DisplayName("Should query actor's current state immediately")
    @Test
    @Order(5)
    public void testQueryCurrentStateImmediately() throws InterruptedException, ExecutionException, TimeoutException {
        ActorRef<AtomicInteger> counter = system.actorOf("counter", new AtomicInteger(0));

        // Start long-running task (increment counter 1000 times)
        CompletableFuture<Void> longTask = counter.tell((AtomicInteger c) -> {
            for (int i = 0; i < 1000; i++) {
                c.incrementAndGet();
                try {
                    Thread.sleep(10); // Total 10 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread.sleep(100); // Wait for task to start

        // Query current value immediately with askNow
        CompletableFuture<Integer> query = counter.askNow((AtomicInteger c) -> c.get());
        int currentValue = query.get(1, TimeUnit.SECONDS);

        // Verify we got a value without waiting for long task to complete
        assertTrue(currentValue > 0, "Value should be greater than 0 (increments in progress)");
        assertTrue(currentValue < 1000, "Value should be less than 1000 (not yet complete)");

        // Cancel the long task to speed up test
        longTask.cancel(true);
    }

    /**
     * Example 6: Stop a long-running task.
     *
     * Situation: A long-running task is executing, and we want to stop it from outside
     * Expected: tellNow() allows sending a stop command that skips the queue
     */
    @DisplayName("Should stop a long-running task")
    @Test
    @Order(6)
    public void testStopLongRunningTask() throws InterruptedException, ExecutionException, TimeoutException {
        AtomicBoolean shouldStop = new AtomicBoolean(false);
        AtomicInteger processedCount = new AtomicInteger(0);

        ActorRef<String> actor = system.actorOf("processor", "data");

        // Start long-running task
        CompletableFuture<Void> longTask = actor.tell((String s) -> {
            for (int i = 0; i < 10000; i++) {
                if (shouldStop.get()) {
                    break; // Stop
                }
                processedCount.incrementAndGet();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        Thread.sleep(100); // Wait for task to start

        // Send stop command with tellNow
        CompletableFuture<Void> stopCommand = actor.tellNow((String s) -> shouldStop.set(true));
        stopCommand.get(1, TimeUnit.SECONDS);

        // Wait for long task to complete
        longTask.get(5, TimeUnit.SECONDS);

        // Verify the task stopped early
        int finalCount = processedCount.get();
        assertTrue(finalCount < 10000, "Processing should stop before 10000 (actual: " + finalCount + ")");
        assertTrue(finalCount > 0, "Some processing should have occurred (actual: " + finalCount + ")");
    }
}
