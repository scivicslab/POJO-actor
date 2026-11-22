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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a message thrown away before it runs still ends its caller's wait: a message
 * discarded on request is cancelled, a message discarded because its actor or system is closing
 * fails with {@link ActorClosedException}, and a message sent to a closed actor fails at once.
 * Every wait in these tests has a timeout, so the old behaviour -- a future that never
 * completes -- shows up as a failure rather than a hang.
 */
@Tag("ActorRef_260509_oo01")
@DisplayName("ActorRef — a discarded message completes its future")
class DiscardedMessagesTest {

    private ActorSystem system;
    private ManagedThreadPool pool;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        if (pool != null) {
            pool.shutdownNow();
        }
        if (system != null) {
            system.terminate();
        }
    }

    /** An action that holds its thread until the test releases it or interrupts it. */
    private void holdUntilReleased() {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the future with a timeout and returns the cause it failed with. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        return e.getCause();
    }

    /** Waits for the future with a timeout and checks that it was cancelled. */
    private static void assertCancelled(CompletableFuture<?> future) {
        assertThrows(CancellationException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(future.isCancelled());
    }

    @Test
    @DisplayName("close(): a message still queued fails with ActorClosedException naming the actor")
    void close_queuedMessage_failsWithActorClosedException() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("closing", new StringBuilder("data"));
        CountDownLatch started = new CountDownLatch(1);
        actor.tell(sb -> {
            started.countDown();
            holdUntilReleased();
        });
        CompletableFuture<Integer> queuedAsk = actor.ask(StringBuilder::length);
        CompletableFuture<Void> queuedTell = actor.tell(sb -> sb.append("x"));
        assertTrue(started.await(3, TimeUnit.SECONDS));

        actor.close();

        Throwable askCause = failureOf(queuedAsk);
        assertInstanceOf(ActorClosedException.class, askCause);
        assertTrue(askCause.getMessage().contains("closing"), askCause.getMessage());
        assertEquals("closing", ((ActorClosedException) askCause).getActorName());
        assertInstanceOf(ActorClosedException.class, failureOf(queuedTell));
    }

    @Test
    @DisplayName("clearPendingMessages(): a message still queued is cancelled")
    void clearPendingMessages_queuedMessage_isCancelled() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("clearing", new StringBuilder("data"));
        CountDownLatch started = new CountDownLatch(1);
        actor.tell(sb -> {
            started.countDown();
            holdUntilReleased();
        });
        CompletableFuture<Integer> queuedAsk = actor.ask(StringBuilder::length);
        CompletableFuture<Void> queuedTell = actor.tell(sb -> sb.append("x"));
        assertTrue(started.await(3, TimeUnit.SECONDS));

        assertEquals(2, actor.clearPendingMessages());

        assertCancelled(queuedAsk);
        assertCancelled(queuedTell);
        release.countDown();
        assertEquals(4, actor.ask(StringBuilder::length).get(3, TimeUnit.SECONDS), "the actor goes on after clearing");
        actor.close();
    }

    @Test
    @DisplayName("cancelJobsForActor(): a pool job still queued is cancelled")
    void cancelJobsForActor_queuedPoolJob_isCancelled() throws Exception {
        pool = new ManagedThreadPool(1);
        ActorRef<StringBuilder> blocker = new ActorRef<>("blocker", new StringBuilder());
        ActorRef<StringBuilder> actor = new ActorRef<>("pooled", new StringBuilder("data"));
        CountDownLatch started = new CountDownLatch(1);
        blocker.tell(sb -> {
            started.countDown();
            holdUntilReleased();
        }, pool);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        CompletableFuture<Void> queuedTell = actor.tell(sb -> sb.append("x"), pool);
        CompletableFuture<Integer> queuedAsk = actor.ask(StringBuilder::length, pool);

        assertEquals(2, pool.cancelJobsForActor("pooled"));

        assertCancelled(queuedTell);
        assertCancelled(queuedAsk);
        blocker.close();
        actor.close();
    }

    @Test
    @DisplayName("terminate(): a pool job still queued fails with ActorClosedException")
    void terminate_queuedPoolJob_failsWithActorClosedException() throws Exception {
        system = new ActorSystem("terminating", 1);
        ExecutorService systemPool = system.getManagedThreadPool();
        ActorRef<StringBuilder> blocker = system.actorOf("blocker", new StringBuilder());
        ActorRef<StringBuilder> actor = system.actorOf("pooledInSystem", new StringBuilder("data"));
        CountDownLatch started = new CountDownLatch(1);
        blocker.tell(sb -> {
            started.countDown();
            holdUntilReleased();
        }, systemPool);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        CompletableFuture<Integer> queuedAsk = actor.ask(StringBuilder::length, systemPool);

        system.terminate();
        system = null;

        Throwable cause = failureOf(queuedAsk);
        assertInstanceOf(ActorClosedException.class, cause);
        assertTrue(cause.getMessage().contains("pooledInSystem"), cause.getMessage());
    }

    @Test
    @DisplayName("tell, ask, tellNow and askNow after close() fail at once with ActorClosedException")
    void messagesAfterClose_failWithActorClosedException() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("closed", new StringBuilder("data"));
        actor.close();

        CompletableFuture<Integer> ask = actor.ask(StringBuilder::length);
        CompletableFuture<Void> tell = actor.tell(sb -> sb.append("x"));
        CompletableFuture<Void> tellNow = actor.tellNow(sb -> sb.append("x"));
        CompletableFuture<Integer> askNow = actor.askNow(StringBuilder::length);

        assertTrue(ask.isCompletedExceptionally(), "ask should return a future that has already failed");
        assertTrue(tell.isCompletedExceptionally(), "tell should return a future that has already failed");
        assertInstanceOf(ActorClosedException.class, failureOf(ask));
        assertInstanceOf(ActorClosedException.class, failureOf(tell));
        assertInstanceOf(ActorClosedException.class, failureOf(tellNow));
        assertInstanceOf(ActorClosedException.class, failureOf(askNow));
    }
}
