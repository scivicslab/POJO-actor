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

public class ClearPendingMessagesTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("clearTestSystem", 2);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void testClearPendingMessagesRemovesQueuedMessages() throws Exception {
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicBoolean longTaskStarted = new AtomicBoolean(false);
        List<Integer> processedMessages = Collections.synchronizedList(new ArrayList<>());

        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Start a long-running task to block the actor
        CompletableFuture<Void> longTask = actor.tell(s -> {
            longTaskStarted.set(true);
            try {
                Thread.sleep(500); // Long running task
                processedMessages.add(0); // Mark this as message 0
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait for the long task to start
        while (!longTaskStarted.get()) {
            Thread.sleep(10);
        }

        // Queue several messages while the long task is running
        List<CompletableFuture<Void>> queuedTasks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final int messageNum = i;
            CompletableFuture<Void> task = actor.tell(s -> {
                processedMessages.add(messageNum);
                processedCount.incrementAndGet();
            });
            queuedTasks.add(task);
        }

        // Give a moment for messages to be queued
        Thread.sleep(50);

        // Clear pending messages
        int clearedCount = actor.clearPendingMessages();

        // Should have cleared the 5 queued messages
        assertEquals(5, clearedCount, "Should have cleared 5 pending messages");

        // Wait for the long task to complete
        longTask.get(2, TimeUnit.SECONDS);

        // Wait a bit more to see if any cleared messages get processed
        Thread.sleep(200);

        // Only the long-running task (message 0) should have been processed
        assertEquals(1, processedMessages.size(), "Only the running task should have completed");
        assertTrue(processedMessages.contains(0), "The long-running task should have completed");
        assertEquals(0, processedCount.get(), "No queued messages should have been processed");
    }

    @Test
    void testClearPendingMessagesDoesNotAffectCurrentlyRunningMessage() throws Exception {
        AtomicBoolean taskCompleted = new AtomicBoolean(false);
        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Start a message that will run for a while
        CompletableFuture<Void> runningTask = actor.tell(s -> {
            try {
                Thread.sleep(300);
                taskCompleted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Give the task a moment to start
        Thread.sleep(50);

        // Clear pending messages (should not affect the running task)
        int clearedCount = actor.clearPendingMessages();

        // No messages should be cleared since only one is running
        assertEquals(0, clearedCount, "No messages should be cleared when only one is running");

        // Wait for the running task to complete
        runningTask.get(1, TimeUnit.SECONDS);

        // The task should have completed successfully
        assertTrue(taskCompleted.get(), "The running task should have completed");
    }

    @Test
    void testClearPendingMessagesOnlyAffectsSpecificActor() throws Exception {
        AtomicInteger actor1Processed = new AtomicInteger(0);
        AtomicInteger actor2Processed = new AtomicInteger(0);

        // Create two different actors
        ActorRef<AtomicInteger> actor1 = system.actorOf("actor1", new AtomicInteger(0));
        ActorRef<AtomicInteger> actor2 = system.actorOf("actor2", new AtomicInteger(0));

        // Queue messages to both actors
        List<CompletableFuture<Void>> actor1Tasks = new ArrayList<>();
        List<CompletableFuture<Void>> actor2Tasks = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            actor1Tasks.add(actor1.tell(counter -> {
                try {
                    Thread.sleep(100);
                    actor1Processed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            actor2Tasks.add(actor2.tell(counter -> {
                try {
                    Thread.sleep(100);
                    actor2Processed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Give messages time to be queued
        Thread.sleep(50);

        // Clear pending messages only from actor1
        int clearedFromActor1 = actor1.clearPendingMessages();

        // Wait for all tasks to complete
        Thread.sleep(1000);

        // Actor1 should have had messages cleared, actor2 should not
        assertTrue(clearedFromActor1 > 0, "Actor1 should have had messages cleared");

        // Actor2 should process all its messages normally
        assertEquals(3, actor2Processed.get(), "Actor2 should process all messages");

        // Actor1 should process fewer messages due to clearing
        assertTrue(actor1Processed.get() < 3, "Actor1 should process fewer messages due to clearing");
    }

    @Test
    void testClearPendingMessagesReturnValue() throws Exception {
        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Queue several messages
        for (int i = 0; i < 10; i++) {
            actor.tell(s -> {
                try {
                    Thread.sleep(1000); // Long delay to keep them queued
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Give time for messages to be queued
        Thread.sleep(100);

        // Clear pending messages
        int clearedCount = actor.clearPendingMessages();

        // Should return a number close to 10 (might be 9 if one started processing)
        assertTrue(clearedCount >= 9, "Should have cleared most or all queued messages, cleared: " + clearedCount);
    }

    @Test
    void testClearPendingMessagesOnEmptyQueue() throws Exception {
        ActorRef<String> actor = system.actorOf("testActor", "test");

        // Clear pending messages when queue is empty
        int clearedCount = actor.clearPendingMessages();

        // Should return 0
        assertEquals(0, clearedCount, "Should return 0 when clearing empty queue");
    }
}