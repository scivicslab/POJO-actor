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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that work sent with {@code tell}/{@code ask(action, ws)} before {@link ActorRef#close()}
 * does not run against the closed actor: a job not yet started on a {@link ManagedThreadPool} or
 * on another executor fails with {@link ActorClosedException} instead of calling the actor. A job
 * that has already started is not interrupted, and close() returns only after it has ended, so
 * no action starts after close() has returned.
 *
 * <p>The interleaving is forced with latches: the pool's single real thread, or the executor that
 * holds the work, is kept busy until after the actor is closed. No test sleeps or retries.</p>
 */
@Tag("ActorRef_260509_oo01")
@DisplayName("ActorRef — pool and executor work sent before close() does not run against the closed actor")
class PoolJobsLeftByCloseTest {

    private static final int N = 5;

    private ActorSystem system;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        if (system != null) {
            system.terminate();
        }
    }

    /** The POJO: counts how often an action reached it. */
    static final class Target {
        final AtomicInteger calls = new AtomicInteger();

        int call() {
            return calls.incrementAndGet();
        }
    }

    /**
     * An executor that holds every task until {@link #release()}, then runs each on a new
     * virtual thread. It stands for a pool whose real threads are all busy, without making one.
     */
    static final class HoldingExecutor extends AbstractExecutorService {
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

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    /** Waits for the future with a timeout and returns the cause it failed with. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS),
                "the future should complete exceptionally");
        return e.getCause();
    }

    /** Blocks the pool's only real thread until {@link #release} is counted down. */
    private void occupyTheOnlyRealThread(ManagedThreadPool pool) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        started.await(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("close() fails the actor's ManagedThreadPool jobs that have not started")
    void queuedPoolJobsFailOnClose() throws Exception {
        system = new ActorSystem("left-by-close", 1);
        ManagedThreadPool pool = (ManagedThreadPool) system.getManagedThreadPool();
        Target target = new Target();
        ActorRef<Target> actor = system.actorOf("worker", target);

        occupyTheOnlyRealThread(pool);
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            futures.add(actor.tell(Target::call, pool));
            futures.add(actor.ask(Target::call, pool));
        }

        actor.close();
        release.countDown();
        // The pool has one real thread and runs its queue in order: once this has run, every job
        // queued before it has run or been removed.
        pool.submit(() -> { }).get(3, TimeUnit.SECONDS);

        assertEquals(0, target.calls.get(), "no pool job may call the closed actor");
        for (CompletableFuture<?> future : futures) {
            assertInstanceOf(ActorClosedException.class, failureOf(future));
        }
        assertEquals(0, pool.getPendingJobCountForActor("worker"));
    }

    @Test
    @DisplayName("work queued on another executor checks, before it runs, that the actor is open")
    void heldExecutorJobsFailAfterClose() throws Exception {
        HoldingExecutor ws = new HoldingExecutor();
        Target target = new Target();
        ActorRef<Target> actor = new ActorRef<>("worker", target);

        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            futures.add(actor.tell(Target::call, ws));
            futures.add(actor.ask(Target::call, ws));
        }

        actor.close();
        ws.release();

        for (CompletableFuture<?> future : futures) {
            assertInstanceOf(ActorClosedException.class, failureOf(future));
        }
        assertEquals(0, target.calls.get(), "no held job may call the closed actor");
    }

    @Test
    @DisplayName("a pool job already running when close() is called is not interrupted and completes")
    void runningPoolJobFinishes() throws Exception {
        system = new ActorSystem("left-by-close", 1);
        ManagedThreadPool pool = (ManagedThreadPool) system.getManagedThreadPool();
        ActorRef<Target> actor = system.actorOf("worker", new Target());

        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Integer> running = actor.ask(t -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            return t.call();
        }, pool);
        started.await(3, TimeUnit.SECONDS);

        actor.close();
        release.countDown();

        assertEquals(1, running.get(3, TimeUnit.SECONDS));
    }

    /**
     * An action that reports whether it saw close() return while it was still running: it waits
     * for {@code closeReturned}, at most one second. When close() waits for the action, close()
     * cannot return first, and the wait ends by its timeout.
     */
    private static boolean sawCloseReturn(CountDownLatch started, CountDownLatch closeReturned) {
        started.countDown();
        try {
            return closeReturned.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @Test
    @DisplayName("close() returns only after a pool job that has already started has ended")
    void closeWaitsForStartedPoolJob() throws Exception {
        system = new ActorSystem("left-by-close", 1);
        ManagedThreadPool pool = (ManagedThreadPool) system.getManagedThreadPool();
        ActorRef<Target> actor = system.actorOf("worker", new Target());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);

        CompletableFuture<Boolean> saw = actor.ask(t -> sawCloseReturn(started, closeReturned), pool);
        started.await(3, TimeUnit.SECONDS);
        actor.close();
        closeReturned.countDown();

        assertFalse(saw.get(3, TimeUnit.SECONDS), "the pool job saw close() return while it was still running");
    }


}
