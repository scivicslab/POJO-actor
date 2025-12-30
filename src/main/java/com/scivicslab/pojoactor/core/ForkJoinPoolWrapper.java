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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A WorkerPool implementation that wraps ForkJoinPool (work-stealing executor).
 *
 * This is the default implementation that maintains backward compatibility
 * with the original POJO-actor behavior. It uses ForkJoinPool for optimal
 * performance with independent CPU-bound tasks.
 *
 * Note: This implementation does NOT support job cancellation per actor.
 * Use ControllableWorkStealingPool if you need that feature.
 *
 * @author devteam@scivics-lab.com
 * @since 1.0.0
 */
public class ForkJoinPoolWrapper implements WorkerPool {

    private final ForkJoinPool pool;

    /**
     * Creates a ForkJoinPoolWrapper with default parallelism.
     */
    public ForkJoinPoolWrapper() {
        this.pool = (ForkJoinPool) Executors.newWorkStealingPool();
    }

    /**
     * Creates a ForkJoinPoolWrapper with specified parallelism.
     *
     * @param parallelism the number of worker threads
     */
    public ForkJoinPoolWrapper(int parallelism) {
        this.pool = (ForkJoinPool) Executors.newWorkStealingPool(parallelism);
    }

    @Override
    public boolean supportsCancellation() {
        return false;
    }

    // Delegate all ExecutorService methods to ForkJoinPool

    @Override
    public void execute(Runnable command) {
        pool.execute(command);
    }

    @Override
    public void shutdown() {
        pool.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return pool.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return pool.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return pool.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return pool.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return pool.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return pool.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return pool.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return pool.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return pool.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return pool.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return pool.invokeAny(tasks, timeout, unit);
    }
}
