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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code ask(action, pool)} and {@code tell(action, pool)} called from one of the
 * pool's own real threads run the action where they are, so that a task waiting on work it gave
 * to its own pool cannot deadlock the pool; and that a call from any other real thread or virtual
 * thread still runs the action on one of the pool's real threads.
 */
@Tag("ManagedThreadPool_260508_oo01")
@DisplayName("ManagedThreadPool — work given to the pool from its own real thread runs inline")
class PoolInlineTest {

    private ManagedThreadPool pool;
    private ManagedThreadPool otherPool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        if (otherPool != null) {
            otherPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ownsCurrentThread() is true on the pool's real threads only")
    void ownsCurrentThread_trueOnlyOnThePoolsRealThreads() throws Exception {
        pool = new ManagedThreadPool(1);
        otherPool = new ManagedThreadPool(1);

        assertFalse(pool.ownsCurrentThread(), "the test's own real thread");
        assertTrue(pool.submit(pool::ownsCurrentThread).get(3, TimeUnit.SECONDS));
        assertFalse(otherPool.submit(pool::ownsCurrentThread).get(3, TimeUnit.SECONDS), "another pool's real thread");
        boolean[] onVirtual = new boolean[1];
        Thread virtual = Thread.ofVirtual().start(() -> onVirtual[0] = pool.ownsCurrentThread());
        virtual.join(3000);
        assertFalse(onVirtual[0], "a virtual thread");
    }

    @Test
    @DisplayName("ask(action, pool) from inside a task on a 1-thread pool completes instead of deadlocking")
    void ask_fromTaskOnOneThreadPool_completes() throws Exception {
        pool = new ManagedThreadPool(1);
        ActorRef<StringBuilder> actor = new ActorRef<>("inlineAsk", new StringBuilder("data"));

        Future<Integer> outer = pool.submit(() -> actor.ask(StringBuilder::length, pool).get());

        assertEquals(4, outer.get(3, TimeUnit.SECONDS));
        actor.close();
    }

    @Test
    @DisplayName("tell(action, pool) from inside a task on a 1-thread pool completes instead of deadlocking")
    void tell_fromTaskOnOneThreadPool_completes() throws Exception {
        pool = new ManagedThreadPool(1);
        ActorRef<StringBuilder> actor = new ActorRef<>("inlineTell", new StringBuilder("data"));

        Future<String> outer = pool.submit(() -> {
            actor.tell(sb -> sb.append("!"), pool).get();
            return actor.ask(StringBuilder::toString).get();
        });

        assertEquals("data!", outer.get(3, TimeUnit.SECONDS));
        actor.close();
    }

    @Test
    @DisplayName("an action run inline that throws completes its future exceptionally")
    void ask_inlineActionThrows_futureCompletesExceptionally() throws Exception {
        pool = new ManagedThreadPool(1);
        ActorRef<StringBuilder> actor = new ActorRef<>("inlineThrow", new StringBuilder("data"));
        StackOverflowError error = new StackOverflowError("test overflow");

        Future<CompletableFuture<Integer>> outer = pool.submit(() -> actor.ask(sb -> { throw error; }, pool));

        CompletableFuture<Integer> future = outer.get(3, TimeUnit.SECONDS);
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertSame(error, e.getCause());
        actor.close();
    }

    @Test
    @DisplayName("ask(action, pool) from a virtual thread or another real thread still runs on the pool")
    void ask_fromOutsideThePool_runsOnThePool() throws Exception {
        pool = new ManagedThreadPool(1);
        otherPool = new ManagedThreadPool(1);
        ActorRef<StringBuilder> actor = new ActorRef<>("outsideAsk", new StringBuilder("data"));

        assertTrue(actor.ask(sb -> pool.ownsCurrentThread(), pool).get(3, TimeUnit.SECONDS),
                "called from the test's real thread");
        AtomicBoolean fromVirtual = new AtomicBoolean();
        Thread virtual = Thread.ofVirtual().start(() -> {
            try {
                fromVirtual.set(actor.ask(sb -> pool.ownsCurrentThread(), pool).get(3, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        virtual.join(5000);
        assertTrue(fromVirtual.get(), "called from a virtual thread");
        assertTrue(otherPool.submit(() -> actor.ask(sb -> pool.ownsCurrentThread(), pool).get(3, TimeUnit.SECONDS))
                .get(5, TimeUnit.SECONDS), "called from another pool's real thread");
        assertInstanceOf(Boolean.class, actor.ask(sb -> pool.ownsCurrentThread(), pool).get(3, TimeUnit.SECONDS));
        actor.close();
    }
}
