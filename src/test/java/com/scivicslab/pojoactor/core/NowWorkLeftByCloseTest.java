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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code tellNow}/{@code askNow} work, which runs on its own virtual thread outside
 * the mailbox, does not run against a closed actor: work that has not started when close() is
 * called fails with {@link ActorClosedException} instead of calling the actor, and work that has
 * started is waited for, so that none starts after close() has returned.
 *
 * <p>The interleaving is forced with latches and with an executor that holds the work until the
 * test releases it. No test sleeps or retries.</p>
 */
@Tag("ActorRef_260509_oo01")
@DisplayName("ActorRef — tellNow/askNow work does not run against the closed actor")
class NowWorkLeftByCloseTest {

    private static final int N = 5;

    /** The POJO: counts how often an action reached it. */
    static final class Target {
        final AtomicInteger calls = new AtomicInteger();

        int call() {
            return calls.incrementAndGet();
        }
    }

    /** An executor that holds every task until {@link #release()}, then runs each on a new virtual thread. */
    static final class HoldingExecutor implements Executor {
        private final List<Runnable> held = new ArrayList<>();
        private boolean released;

        @Override
        public synchronized void execute(Runnable task) {
            if (released) {
                Thread.ofVirtual().start(task);
            } else {
                held.add(task);
            }
        }

        synchronized void release() {
            released = true;
            held.forEach(task -> Thread.ofVirtual().start(task));
            held.clear();
        }
    }

    /** An actor whose tellNow/askNow work goes to a {@link HoldingExecutor}. */
    static final class HeldNowActorRef<T> extends ActorRef<T> {
        private final HoldingExecutor now;

        HeldNowActorRef(String name, T object, HoldingExecutor now) {
            super(name, object);
            this.now = now;
        }

        @Override
        Executor nowExecutor() {
            return now;
        }
    }

    /** Waits for the future with a timeout and returns the cause it failed with. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS),
                "the future should complete exceptionally");
        return e.getCause();
    }

    @Test
    @DisplayName("tellNow and askNow work that has not started fails after close()")
    void nowWorkFailsAfterClose() throws Exception {
        HoldingExecutor now = new HoldingExecutor();
        Target target = new Target();
        ActorRef<Target> actor = new HeldNowActorRef<>("worker", target, now);

        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            futures.add(actor.tellNow(Target::call));
            futures.add(actor.askNow(Target::call));
        }

        actor.close();
        now.release();

        for (CompletableFuture<?> future : futures) {
            assertInstanceOf(ActorClosedException.class, failureOf(future));
        }
        assertEquals(0, target.calls.get(), "no tellNow/askNow work may call the closed actor");
    }

    @Test
    @DisplayName("close() returns only after askNow work that has already started has ended")
    void closeWaitsForStartedAskNow() throws Exception {
        ActorRef<Target> actor = new ActorRef<>("worker", new Target());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);

        // The work waits for close() to return, at most one second. When close() waits for the
        // work, close() cannot return first, and the wait ends by its timeout.
        CompletableFuture<Boolean> saw = actor.askNow(t -> {
            started.countDown();
            try {
                return closeReturned.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        });
        started.await(3, TimeUnit.SECONDS);
        actor.close();
        closeReturned.countDown();

        assertFalse(saw.get(3, TimeUnit.SECONDS), "the askNow work saw close() return while it was still running");
    }
}
