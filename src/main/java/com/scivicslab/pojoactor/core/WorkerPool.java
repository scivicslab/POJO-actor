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

import java.util.concurrent.ExecutorService;

/**
 * Extension of ExecutorService that adds per-actor job management.
 *
 * <p>This interface exists primarily for backward compatibility with ForkJoinPool-based
 * implementations. ForkJoinPool uses work-stealing with internally distributed queues
 * that offer no API for external queue manipulation, making per-actor job cancellation
 * impossible. Replacing it with {@link ManagedThreadPool} (ThreadPoolExecutor +
 * LinkedBlockingDeque) enables direct queue access via {@code queue.remove()}, but
 * would break callers that reference {@code ExecutorService} directly.
 *
 * <p>By introducing this interface with no-op default methods, existing ForkJoinPool
 * wrappers continue to work unchanged. Only {@link ManagedThreadPool} overrides the
 * defaults and returns {@code supportsCancellation() == true}.
 *
 * <p>Callers should check {@link #supportsCancellation()} before relying on
 * {@link #cancelJobsForActor(String)}.
 *
 * @author devteam@scivicslab.com
 * @since 2.0.0
 * @see ManagedThreadPool
 */
public interface WorkerPool extends ExecutorService {

    /**
     * Cancels all pending (not yet started) jobs submitted for the given actor.
     * Jobs already running are not interrupted and will run to completion.
     *
     * <p>The default implementation is a no-op for ForkJoinPool-based implementations,
     * which cannot access their internal queues.
     *
     * @param actorName the name of the actor whose pending jobs should be cancelled
     * @return the number of jobs removed from the queue; 0 if unsupported
     */
    default int cancelJobsForActor(String actorName) {
        return 0;
    }

    /**
     * Returns the number of pending (not yet started) jobs for the given actor.
     *
     * <p>The default implementation returns 0 for ForkJoinPool-based implementations.
     *
     * @param actorName the name of the actor
     * @return the number of pending jobs; 0 if unsupported
     */
    default int getPendingJobCountForActor(String actorName) {
        return 0;
    }

    /**
     * Returns whether this implementation supports per-actor job cancellation.
     *
     * <p>Returns {@code false} for ForkJoinPool-based implementations.
     * Returns {@code true} for {@link ManagedThreadPool}.
     *
     * @return true if {@link #cancelJobsForActor(String)} actually removes jobs
     */
    default boolean supportsCancellation() {
        return false;
    }
}
