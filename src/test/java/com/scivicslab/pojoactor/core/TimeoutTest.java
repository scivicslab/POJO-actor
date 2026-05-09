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

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies state S1.06: CompletableFuture timeout patterns work with both tell() and ask().
 */
@Tag("S1.06")
@DisplayName("Timeout — CompletableFuture timeout patterns (S1.06)")
public class TimeoutTest {

    private ActorSystem system;
    private CountDownLatch blocker;

    static class SlowActor {
        void block(CountDownLatch latch) {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        int compute() { return 42; }
        void work() { /* fast */ }
    }

    @BeforeEach
    void setUp() {
        system = new ActorSystem("test");
        blocker = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        blocker.countDown();
        system.terminate();
    }

    // -----------------------------------------------------------------------
    // ask() — パターン1: get(timeout, unit)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ask(): get(timeout, unit) — actor responds in time → value returned")
    void askGetWithTimeout_returnsValue() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        int result = actor.ask(a -> a.compute())
                          .get(2, TimeUnit.SECONDS);

        assertEquals(42, result);
    }

    @Test
    @DisplayName("ask(): get(timeout, unit) — actor does not respond → TimeoutException")
    void askGetWithTimeout_throwsOnTimeout() {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        CompletableFuture<Integer> future = actor.ask(a -> {
            a.block(blocker);
            return a.compute();
        });

        assertThrows(TimeoutException.class,
            () -> future.get(200, TimeUnit.MILLISECONDS));
    }

    // -----------------------------------------------------------------------
    // ask() — パターン2: orTimeout()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ask(): orTimeout() — actor responds in time → thenAccept fires with value")
    void askOrTimeout_firesWithValue() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());
        AtomicReference<Integer> received = new AtomicReference<>();

        actor.ask(a -> a.compute())
             .orTimeout(2, TimeUnit.SECONDS)
             .thenAccept(received::set)
             .get(2, TimeUnit.SECONDS);

        assertEquals(42, received.get());
    }

    @Test
    @DisplayName("ask(): orTimeout() — actor does not respond → completes exceptionally with TimeoutException")
    void askOrTimeout_completesExceptionally() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        CompletableFuture<Integer> future = actor
            .ask(a -> { a.block(blocker); return a.compute(); })
            .orTimeout(200, TimeUnit.MILLISECONDS);

        Thread.sleep(300);

        assertTrue(future.isCompletedExceptionally());
    }

    // -----------------------------------------------------------------------
    // ask() — パターン3: completeOnTimeout()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ask(): completeOnTimeout() — actor responds in time → real value returned")
    void askCompleteOnTimeout_returnsRealValue() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        int result = actor.ask(a -> a.compute())
                          .completeOnTimeout(-1, 2, TimeUnit.SECONDS)
                          .get();

        assertEquals(42, result);
    }

    @Test
    @DisplayName("ask(): completeOnTimeout() — actor does not respond → default value returned")
    void askCompleteOnTimeout_returnsDefault() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        int result = actor
            .ask(a -> { a.block(blocker); return a.compute(); })
            .completeOnTimeout(-1, 200, TimeUnit.MILLISECONDS)
            .get();

        assertEquals(-1, result);
    }

    // -----------------------------------------------------------------------
    // tell() — パターン1: get(timeout, unit)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tell(): get(timeout, unit) — actor finishes in time → completes normally")
    void tellGetWithTimeout_completesNormally() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        actor.tell(a -> a.work())
             .get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("tell(): get(timeout, unit) — actor does not respond → TimeoutException")
    void tellGetWithTimeout_throwsOnTimeout() {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        CompletableFuture<Void> future = actor.tell(a -> a.block(blocker));

        assertThrows(TimeoutException.class,
            () -> future.get(200, TimeUnit.MILLISECONDS));
    }

    // -----------------------------------------------------------------------
    // tell() — パターン2: orTimeout()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tell(): orTimeout() — actor finishes in time → completes normally")
    void tellOrTimeout_completesNormally() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        actor.tell(a -> a.work())
             .orTimeout(2, TimeUnit.SECONDS)
             .get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("tell(): orTimeout() — actor does not respond → completes exceptionally")
    void tellOrTimeout_completesExceptionally() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        CompletableFuture<Void> future = actor
            .tell(a -> a.block(blocker))
            .orTimeout(200, TimeUnit.MILLISECONDS);

        Thread.sleep(300);

        assertTrue(future.isCompletedExceptionally());
    }

    // -----------------------------------------------------------------------
    // tell() — パターン3: completeOnTimeout()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tell(): completeOnTimeout() — actor finishes in time → null (Void) returned")
    void tellCompleteOnTimeout_completesNormally() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        Void result = actor.tell(a -> a.work())
                           .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                           .get();

        assertNull(result);
    }

    @Test
    @DisplayName("tell(): completeOnTimeout() — actor does not respond → null (Void) returned")
    void tellCompleteOnTimeout_returnsNullOnTimeout() throws Exception {
        ActorRef<SlowActor> actor = system.actorOf("slow", new SlowActor());

        Void result = actor
            .tell(a -> a.block(blocker))
            .completeOnTimeout(null, 200, TimeUnit.MILLISECONDS)
            .get();

        assertNull(result);
    }
}
