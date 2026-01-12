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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * Test class for verifying ActorRef functionality.
 * This test suite covers actor creation, messaging (tell/ask),
 * message ordering, child actors, and lifecycle management.
 *
 * @author devteam@scivics-lab.com
 * @version 2.7.0
 */
@DisplayName("ActorRef functionality tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ActorRefTest {

    private static final Logger logger = Logger.getLogger(ActorRefTest.class.getName());
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
     * Example 1: Create actors directly without ActorSystem.
     *
     * Situation: Creating an actor without using ActorSystem
     * Expected: Actor is created and can be used independently
     */
    @DisplayName("Should create actors directly without ActorSystem")
    @Test
    @Order(1)
    public void testDirectActorCreation() {
        // Create actor directly
        ActorRef<ArrayList<Integer>> actor = new ActorRef<>("myActor", new ArrayList<Integer>());

        // Verify actor name
        assertEquals("myActor", actor.getName(), "Actor name should match");

        // Verify actor is alive
        assertTrue(actor.isAlive(), "Actor should be alive after creation");

        // Close actor
        actor.close();
        assertFalse(actor.isAlive(), "Actor should not be alive after close");
    }

    /**
     * Example 2: tell() for asynchronous message sending.
     *
     * Situation: Sending messages asynchronously (Fire-and-Forget)
     * Expected: Messages are processed and CompletableFuture completes
     */
    @DisplayName("Should send messages asynchronously with tell()")
    @Test
    @Order(2)
    public void testTellAsyncMessaging() throws InterruptedException, ExecutionException, TimeoutException {
        ActorRef<ArrayList<Integer>> actor = new ActorRef<>("counter", new ArrayList<Integer>());

        // Send messages with tell()
        CompletableFuture<Void> f1 = actor.tell(list -> list.add(1));
        CompletableFuture<Void> f2 = actor.tell(list -> list.add(2));
        CompletableFuture<Void> f3 = actor.tell(list -> list.add(3));

        // Wait for all messages to be processed
        CompletableFuture.allOf(f1, f2, f3).get(3, TimeUnit.SECONDS);

        // Get result
        CompletableFuture<String> result = actor.ask(list -> list.toString());
        String value = result.get(3, TimeUnit.SECONDS);

        assertEquals("[1, 2, 3]", value, "Messages should be processed in order");

        actor.close();
    }

    /**
     * Example 3: ask() for request-response pattern.
     *
     * Situation: Requesting a response from an actor
     * Expected: Actor processes request and returns result
     */
    @DisplayName("Should support request-response with ask()")
    @Test
    @Order(3)
    public void testAskRequestResponse() throws InterruptedException, ExecutionException, TimeoutException {
        ActorRef<StringBuilder> actor = new ActorRef<>("builder", new StringBuilder());

        // Modify state with tell()
        actor.tell(sb -> sb.append("Hello, "));
        actor.tell(sb -> sb.append("World!"));

        // Get result with ask()
        CompletableFuture<String> future = actor.ask(sb -> sb.toString());
        String result = future.get(3, TimeUnit.SECONDS);

        assertEquals("Hello, World!", result, "ask() should return the correct result");

        // Get computed result
        CompletableFuture<Integer> lengthFuture = actor.ask(sb -> sb.length());
        int length = lengthFuture.get(3, TimeUnit.SECONDS);

        assertEquals(13, length, "ask() should return computed value");

        actor.close();
    }

    /**
     * Example 4: Message ordering guarantee.
     *
     * Situation: Sending multiple messages with varying processing times
     * Expected: Messages are processed in FIFO order
     */
    @DisplayName("Should process messages in FIFO order")
    @Test
    @Order(4)
    public void testMessageOrdering() throws InterruptedException, ExecutionException, TimeoutException {
        ActorRef<ArrayList<Integer>> actor = new ActorRef<>("orderedActor", new ArrayList<Integer>());

        // Send messages with random processing times
        for (int i = 0; i < 10; i++) {
            final int value = i;
            actor.tell(list -> {
                // Random sleep (0-50ms) to simulate varying processing times
                try {
                    Thread.sleep((long) (Math.random() * 50));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                list.add(value);
            });
        }

        // Get result
        CompletableFuture<String> future = actor.ask(list -> list.toString());
        String result = future.get(10, TimeUnit.SECONDS);

        assertEquals("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]", result,
            "Messages should be processed in order regardless of processing time");

        actor.close();
    }

    /**
     * Example 5: Create child actors.
     *
     * Situation: Creating child actors from a parent actor
     * Expected: Child actors are created with proper parent-child relationships
     */
    @DisplayName("Should create child actors with proper relationships")
    @Test
    @Order(5)
    public void testChildActorCreation() throws InterruptedException, ExecutionException, TimeoutException {
        ActorRef<String> parent = system.actorOf("parent", "parentData");

        // Create child actors
        ActorRef<ArrayList<Integer>> child1 = parent.createChild("child1", new ArrayList<Integer>());
        ActorRef<ArrayList<String>> child2 = parent.createChild("child2", new ArrayList<String>());

        // Use child actor
        child1.tell(list -> list.add(100));
        CompletableFuture<String> result = child1.ask(list -> list.toString());
        assertEquals("[100]", result.get(3, TimeUnit.SECONDS), "Child actor should process messages");

        // Verify parent-child relationships
        assertEquals("parent", child1.getParentName(), "Child should have correct parent name");
        assertEquals("parent", child2.getParentName(), "Child should have correct parent name");

        // Verify parent knows about children
        ConcurrentSkipListSet<String> children = parent.getNamesOfChildren();
        assertTrue(children.contains("child1"), "Parent should know about child1");
        assertTrue(children.contains("child2"), "Parent should know about child2");

        // Verify children are registered in system
        assertNotNull(system.getActor("child1"), "child1 should be registered in system");
        assertNotNull(system.getActor("child2"), "child2 should be registered in system");
    }

    /**
     * Example 6: Close actor and cleanup resources.
     *
     * Situation: Closing an actor that is no longer needed
     * Expected: Actor is closed and removed from system
     */
    @DisplayName("Should close actor and cleanup resources")
    @Test
    @Order(6)
    public void testActorClose() throws InterruptedException, ExecutionException, TimeoutException {
        ActorRef<ArrayList<Integer>> actor = system.actorOf("tempActor", new ArrayList<Integer>());

        // Use actor
        actor.tell(list -> list.add(1));
        CompletableFuture<String> result = actor.ask(list -> list.toString());
        result.get(3, TimeUnit.SECONDS);

        // Verify actor is alive
        assertTrue(actor.isAlive(), "Actor should be alive before close");
        assertTrue(system.hasActor("tempActor"), "Actor should be in system before close");

        // Close actor
        actor.close();

        // Verify actor is closed
        assertFalse(actor.isAlive(), "Actor should not be alive after close");
        assertFalse(system.hasActor("tempActor"), "Actor should be removed from system after close");
    }

    /**
     * Example 7: Exception handling in tell().
     *
     * Situation: Exception occurs during message processing
     * Expected: CompletableFuture completes exceptionally, but actor survives
     */
    @DisplayName("Should handle exceptions in tell() properly")
    @Test
    @Order(7)
    public void testExceptionHandling() throws InterruptedException, TimeoutException {
        ActorRef<String> actor = new ActorRef<>("errorActor", "data");

        // Send message that throws exception
        CompletableFuture<Void> future = actor.tell(s -> {
            throw new RuntimeException("Test error");
        });

        // Verify exception is captured in CompletableFuture
        boolean completedExceptionally = false;
        try {
            future.get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            completedExceptionally = true;
            assertTrue(e.getCause() instanceof RuntimeException, "Cause should be RuntimeException");
            assertEquals("Test error", e.getCause().getMessage(), "Exception message should match");
        }

        assertTrue(completedExceptionally, "Future should complete exceptionally");

        // Verify actor is still alive
        assertTrue(actor.isAlive(), "Actor should survive exception");

        // Verify actor can still process messages
        CompletableFuture<Integer> lengthFuture = actor.ask(s -> s.length());
        try {
            int length = lengthFuture.get(3, TimeUnit.SECONDS);
            assertEquals(4, length, "Actor should still process messages after exception");
        } catch (ExecutionException e) {
            // This should not happen
            throw new AssertionError("Actor should be able to process messages after exception", e);
        }

        actor.close();
    }

    /**
     * Example 8: CPU-intensive tasks with WorkStealingPool.
     *
     * Situation: Running CPU-intensive tasks in parallel
     * Expected: Tasks are executed in parallel on the WorkStealingPool
     */
    @DisplayName("Should execute CPU-intensive tasks with WorkStealingPool")
    @Test
    @Order(8)
    public void testWorkStealingPoolExecution() throws InterruptedException, ExecutionException, TimeoutException {
        ActorRef<AtomicInteger> actor = system.actorOf("counter", new AtomicInteger(0));

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Submit CPU-intensive tasks to WorkStealingPool
        for (int i = 0; i < 10; i++) {
            CompletableFuture<Void> future = actor.tell(counter -> {
                // Simulate CPU-intensive work (~30ms)
                long endTime = System.currentTimeMillis() + 30;
                while (System.currentTimeMillis() < endTime) {
                    Math.sqrt(Math.random());
                }
                counter.incrementAndGet();
            }, system.getManagedThreadPool());
            futures.add(future);
        }

        // Wait for all tasks to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

        // Verify counter value
        int finalCount = actor.askNow(AtomicInteger::get).get(1, TimeUnit.SECONDS);
        assertEquals(10, finalCount, "All tasks should be completed");
    }
}
