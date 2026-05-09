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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies state S1.01: WorkerPool interface is defined with backward-compatible defaults.
 *
 * <p>The key design constraint: ForkJoinPool-based implementations must be usable as
 * WorkerPool without any changes. The default methods return safe no-op values so
 * that callers who check {@link WorkerPool#supportsCancellation()} first will behave
 * correctly regardless of the underlying implementation.
 */
@Tag("S1.01")
@DisplayName("WorkerPool — interface defaults (S1.01)")
public class WorkerPoolTest {

    /**
     * Minimal WorkerPool that does not override any default methods.
     * Represents a ForkJoinPool-based wrapper: gains WorkerPool compatibility purely
     * through the interface, with no per-actor tracking or cancellation capability.
     */
    static class NoOpWorkerPool implements WorkerPool {
        @Override public void execute(Runnable command) {}
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public <T> Future<T> submit(Callable<T> task) { return null; }
        @Override public <T> Future<T> submit(Runnable task, T result) { return null; }
        @Override public Future<?> submit(Runnable task) { return null; }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) { return List.of(); }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return List.of(); }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks) { return null; }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return null; }
    }

    /**
     * WorkerPool backed by a real single-threaded executor but with no overrides.
     * Represents a ForkJoinPool wrapper: jobs actually execute, but the pool has no
     * queue API to inspect or cancel individual actor tasks.
     */
    static class ForkJoinPoolWorkerPool implements WorkerPool {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();

        @Override public void execute(Runnable cmd) { delegate.execute(cmd); }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException { return delegate.awaitTermination(t, u); }
        @Override public <T> Future<T> submit(Callable<T> task) { return delegate.submit(task); }
        @Override public <T> Future<T> submit(Runnable task, T result) { return delegate.submit(task, result); }
        @Override public Future<?> submit(Runnable task) { return delegate.submit(task); }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException { return delegate.invokeAll(tasks); }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long t, TimeUnit u) throws InterruptedException { return delegate.invokeAll(tasks, t, u); }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException { return delegate.invokeAny(tasks); }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long t, TimeUnit u) throws InterruptedException, ExecutionException, TimeoutException { return delegate.invokeAny(tasks, t, u); }
    }

    private ForkJoinPoolWorkerPool executingPool;

    @AfterEach
    void tearDown() {
        if (executingPool != null) executingPool.shutdown();
    }

    // --- default method values (NoOpWorkerPool / interface defaults) ---

    @Test
    @DisplayName("cancelJobsForActor() returns 0 by default (ForkJoinPool cannot cancel)")
    void defaultCancelJobsForActorReturnsZero() {
        WorkerPool pool = new NoOpWorkerPool();
        assertEquals(0, pool.cancelJobsForActor("any-actor"));
    }

    @Test
    @DisplayName("getPendingJobCountForActor() returns 0 by default")
    void defaultGetPendingJobCountReturnsZero() {
        WorkerPool pool = new NoOpWorkerPool();
        assertEquals(0, pool.getPendingJobCountForActor("any-actor"));
    }

    @Test
    @DisplayName("supportsCancellation() returns false by default (ForkJoinPool has no queue API)")
    void defaultSupportsCancellationReturnsFalse() {
        WorkerPool pool = new NoOpWorkerPool();
        assertFalse(pool.supportsCancellation());
    }

    // --- behavior with a real executor (ForkJoinPoolWorkerPool) ---

    @Test
    @DisplayName("execute() runs jobs even without per-actor tracking")
    void executeJobsRun() throws Exception {
        executingPool = new ForkJoinPoolWorkerPool();
        CountDownLatch done = new CountDownLatch(3);

        executingPool.execute(done::countDown);
        executingPool.execute(done::countDown);
        executingPool.execute(done::countDown);

        assertTrue(done.await(2, TimeUnit.SECONDS), "Jobs must execute even without tracking");
    }

    @Test
    @DisplayName("getPendingJobCountForActor() returns 0 even when jobs are queued (no queue API)")
    void getPendingJobCountAlwaysZeroEvenWhenQueued() throws Exception {
        executingPool = new ForkJoinPoolWorkerPool();
        CountDownLatch blocking = new CountDownLatch(1);

        executingPool.execute(() -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        executingPool.execute(() -> {});
        executingPool.execute(() -> {});

        Thread.sleep(100);

        assertEquals(0, executingPool.getPendingJobCountForActor("actor1"),
            "ForkJoinPool-style pool has no queue API — always returns 0 regardless of actual queue depth");

        blocking.countDown();
    }

    @Test
    @DisplayName("cancelJobsForActor() returns 0 and queued jobs still execute")
    void cancelJobsForActorIsNoOpAndJobsStillRun() throws Exception {
        executingPool = new ForkJoinPoolWorkerPool();
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger(0);

        executingPool.execute(() -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        executingPool.execute(() -> executed.incrementAndGet());
        executingPool.execute(() -> executed.incrementAndGet());

        Thread.sleep(100);

        int cancelled = executingPool.cancelJobsForActor("actor1");
        assertEquals(0, cancelled, "cancelJobsForActor() must return 0 for non-ManagedThreadPool");

        blocking.countDown();
        Thread.sleep(200);

        assertEquals(2, executed.get(), "Jobs must still execute because cancelJobsForActor() is a no-op");
    }

    // --- ManagedThreadPool vs WorkerPool ---

    @Test
    @DisplayName("ManagedThreadPool reports supportsCancellation() == true")
    void managedThreadPoolSupportsCancellation() {
        ManagedThreadPool pool = new ManagedThreadPool(1);
        assertTrue(pool.supportsCancellation());
        pool.shutdown();
    }

    @Test
    @DisplayName("ManagedThreadPool is assignable to WorkerPool")
    void managedThreadPoolIsWorkerPool() {
        WorkerPool pool = new ManagedThreadPool(1);
        assertInstanceOf(WorkerPool.class, pool);
        pool.shutdown();
    }
}
