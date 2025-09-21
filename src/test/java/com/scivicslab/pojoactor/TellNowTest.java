package com.scivicslab.pojoactor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

public class TellNowTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("tellNowTestSystem", 2);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void testTellNowExecutesImmediately() throws Exception {
        // Counter to track execution order
        AtomicInteger executionOrder = new AtomicInteger(0);
        List<Integer> executionSequence = Collections.synchronizedList(new ArrayList<>());

        // Create an actor with a simple counter
        AtomicInteger counter = new AtomicInteger(0);
        ActorRef<AtomicInteger> actor = system.actorOf("counter", counter);

        // Send several regular tell messages (these should be queued)
        for (int i = 0; i < 5; i++) {
            final int value = i;
            actor.tell(c -> {
                try {
                    Thread.sleep(100); // Simulate some work
                    executionSequence.add(value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Send a tellNow message - this should execute immediately
        CompletableFuture<Void> urgentTask = actor.tellNow(c -> {
            executionSequence.add(999); // Use 999 to identify the urgent task
        });

        // Wait for the urgent task to complete
        urgentTask.get(1, TimeUnit.SECONDS);

        // The urgent task (999) should appear in the execution sequence
        // even though regular tasks might still be running
        assertTrue(executionSequence.contains(999),
                  "Urgent task should have executed");

        // Wait for all tasks to complete
        Thread.sleep(1000);

        // Verify all tasks executed
        assertEquals(6, executionSequence.size(),
                    "All tasks should have executed");
        assertTrue(executionSequence.contains(999),
                  "Urgent task should be in the sequence");
    }

    @Test
    void testTellNowRunsConcurrentlyWithTell() throws Exception {
        AtomicBoolean tellTaskRunning = new AtomicBoolean(false);
        AtomicBoolean tellNowCompleted = new AtomicBoolean(false);

        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Start a long-running tell task
        CompletableFuture<Void> longTask = actor.tell(s -> {
            tellTaskRunning.set(true);
            try {
                Thread.sleep(500); // Long running task
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tellTaskRunning.set(false);
        });

        // Give the long task a moment to start
        Thread.sleep(50);

        // Send tellNow while the long task is running
        CompletableFuture<Void> urgentTask = actor.tellNow(s -> {
            tellNowCompleted.set(true);
        });

        // Wait for the urgent task to complete
        urgentTask.get(1, TimeUnit.SECONDS);

        // The urgent task should complete while the long task is still running
        assertTrue(tellNowCompleted.get(), "TellNow task should have completed");
        assertTrue(tellTaskRunning.get(), "Long tell task should still be running");

        // Wait for the long task to finish
        longTask.get(2, TimeUnit.SECONDS);
    }

    @Test
    void testTellNowWithException() throws Exception {
        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Test that exceptions in tellNow are properly handled
        CompletableFuture<Void> futureWithException = actor.tellNow(s -> {
            throw new RuntimeException("Test exception");
        });

        // The future should complete exceptionally
        assertThrows(Exception.class, () -> {
            futureWithException.get(1, TimeUnit.SECONDS);
        });
    }

    @Test
    void testMultipleTellNowCallsExecuteConcurrently() throws Exception {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        ActorRef<String> actor = system.actorOf("testActor", "test");

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Start multiple tellNow tasks
        for (int i = 0; i < 5; i++) {
            CompletableFuture<Void> future = actor.tellNow(s -> {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));

                try {
                    Thread.sleep(100); // Simulate work
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                concurrentCount.decrementAndGet();
            });
            futures.add(future);
        }

        // Wait for all tasks to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(2, TimeUnit.SECONDS);

        // With concurrent execution, we should see more than 1 task running at the same time
        assertTrue(maxConcurrent.get() > 1,
                  "Multiple tellNow calls should execute concurrently. Max concurrent: " + maxConcurrent.get());
    }
}