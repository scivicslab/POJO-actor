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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-actor job tracking of {@link ManagedThreadPool}: a job the pool refuses is not
 * left tracked, and a job submitted while {@link ManagedThreadPool#cancelJobsForActor(String)}
 * runs is either cancelled or stays tracked -- never queued but untracked.
 *
 * <p>The second interleaving is forced without sleeps: cancelling a job's future runs the
 * caller's dependent action on the cancelling thread, so a dependent action that submits a new
 * job for the same actor runs exactly where cancelJobsForActor() used to be in the middle of its
 * bookkeeping.</p>
 */
@Tag("ManagedThreadPool_260508_oo01")
@DisplayName("ManagedThreadPool — job tracking under rejection and cancellation")
class JobTrackingRaceTest {

    private ManagedThreadPool pool;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** Blocks the pool's only real thread until {@link #release} is counted down. */
    private void occupyTheOnlyRealThread() throws InterruptedException {
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
    @DisplayName("a job the pool refuses is not left tracked")
    void rejectedJobIsNotTracked() {
        pool = new ManagedThreadPool(1);
        pool.shutdown();

        assertThrows(RejectedExecutionException.class,
                () -> pool.submitForActor("refused", () -> { }, new CompletableFuture<>()));

        assertEquals(0, pool.getTrackedActorCount(), "the refused job is still tracked");
    }

    @Test
    @DisplayName("a job submitted while cancelJobsForActor() runs stays tracked and cancellable")
    void jobSubmittedDuringCancelStaysTracked() throws Exception {
        pool = new ManagedThreadPool(1);
        occupyTheOnlyRealThread();
        AtomicInteger ran = new AtomicInteger();

        CompletableFuture<Void> first = new CompletableFuture<>();
        pool.submitForActor("a", ran::incrementAndGet, first);
        // When cancelJobsForActor() cancels the first job's future, this runs on the cancelling
        // thread and submits a second job for the same actor.
        first.whenComplete((r, t) -> pool.submitForActor("a", ran::incrementAndGet, new CompletableFuture<>()));

        assertEquals(1, pool.cancelJobsForActor("a"));

        assertEquals(1, pool.getPendingJobCountForActor("a"), "the second job is queued but no longer tracked");
        assertEquals(1, pool.cancelJobsForActor("a"), "the second job could not be cancelled");

        release.countDown();
        pool.submit(() -> { }).get(3, TimeUnit.SECONDS);
        assertEquals(0, ran.get(), "a cancelled job ran");
    }
}
