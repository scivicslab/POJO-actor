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
 * Interface for worker pools that execute CPU-bound jobs for actors.
 *
 * This interface abstracts two implementation strategies:
 * 1. ForkJoinPool-based (work-stealing, default)
 * 2. ControllableWorkStealingPool (ThreadPoolExecutor-based with job cancellation)
 *
 * Implementations must provide ExecutorService compatibility for use with
 * ActorRef.tell(action, pool) and ActorRef.ask(action, pool).
 *
 * @author devteam@scivics-lab.com
 * @since 1.0.0
 */
public interface WorkerPool extends ExecutorService {

    /**
     * Cancels all pending jobs for a specific actor.
     * Jobs that are already running will continue to completion.
     *
     * @param actorName the name of the actor whose jobs should be cancelled
     * @return the number of jobs that were cancelled
     */
    default int cancelJobsForActor(String actorName) {
        // Default implementation: does nothing (for ForkJoinPool-based implementation)
        return 0;
    }

    /**
     * Gets the number of pending jobs for a specific actor.
     *
     * @param actorName the name of the actor
     * @return the number of pending jobs
     */
    default int getPendingJobCountForActor(String actorName) {
        // Default implementation: returns 0 (for ForkJoinPool-based implementation)
        return 0;
    }

    /**
     * Checks if this worker pool supports job cancellation per actor.
     *
     * @return true if cancelJobsForActor() is supported
     */
    default boolean supportsCancellation() {
        return false;
    }
}
