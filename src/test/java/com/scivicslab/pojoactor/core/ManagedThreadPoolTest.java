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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies state S1.02: ManagedThreadPool tracks jobs per actor and supports cancellation.
 */
@Tag("S_base.02")
@DisplayName("ManagedThreadPool — per-actor job tracking (S1.02)")
public class ManagedThreadPoolTest {

    private ManagedThreadPool pool;

    @BeforeEach
    void setUp() {
        pool = new ManagedThreadPool(1); // 1 thread so jobs queue up
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("submitForActor() tracks jobs in the queue")
    void submitForActorTracksJobs() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);

        pool.submitForActor("actor1", () -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        pool.submitForActor("actor1", () -> {});
        pool.submitForActor("actor1", () -> {});

        Thread.sleep(100);

        assertTrue(pool.getPendingJobCountForActor("actor1") > 0,
            "Queued jobs must be tracked per actor");

        blocking.countDown();
    }

    @Test
    @DisplayName("cancelJobsForActor() removes queued jobs and returns the count")
    void cancelJobsForActorRemovesQueuedJobs() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger(0);

        pool.submitForActor("actor1", () -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        pool.submitForActor("actor1", () -> executed.incrementAndGet());
        pool.submitForActor("actor1", () -> executed.incrementAndGet());
        pool.submitForActor("actor1", () -> executed.incrementAndGet());

        Thread.sleep(100);

        int cancelled = pool.cancelJobsForActor("actor1");
        assertTrue(cancelled > 0, "cancelJobsForActor() must remove queued jobs");

        blocking.countDown();
        Thread.sleep(100);

        assertEquals(0, executed.get(), "Cancelled jobs must not have executed");
    }

    @Test
    @DisplayName("cancelJobsForActor() does not affect already-running jobs")
    void cancelDoesNotStopRunningJob() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger(0);

        pool.submitForActor("actor1", () -> {
            started.countDown();
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            executed.incrementAndGet();
        });

        started.await(1, TimeUnit.SECONDS);
        pool.cancelJobsForActor("actor1");
        blocking.countDown();

        Thread.sleep(100);
        assertEquals(1, executed.get(), "Running job must complete even after cancelJobsForActor()");
    }

    @Test
    @DisplayName("cancelJobsForActor() only affects the specified actor")
    void cancelDoesNotAffectOtherActors() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicInteger actor2Executed = new AtomicInteger(0);

        pool.submitForActor("actor1", () -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        pool.submitForActor("actor1", () -> {});
        pool.submitForActor("actor2", () -> actor2Executed.incrementAndGet());

        Thread.sleep(100);

        pool.cancelJobsForActor("actor1");
        blocking.countDown();

        Thread.sleep(200);
        assertEquals(1, actor2Executed.get(), "actor2's job must not be affected by cancelling actor1");
    }

    @Test
    @DisplayName("submitUrgentForActor() places job at the front of the queue")
    void submitUrgentForActorRunsFirst() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger(0);
        AtomicInteger normalOrder = new AtomicInteger(-1);
        AtomicInteger urgentOrder = new AtomicInteger(-1);

        pool.submitForActor("actor1", () -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        pool.submitForActor("actor1", () -> normalOrder.set(order.incrementAndGet()));
        pool.submitUrgentForActor("actor1", () -> urgentOrder.set(order.incrementAndGet()));

        Thread.sleep(100);
        blocking.countDown();
        Thread.sleep(200);

        assertTrue(urgentOrder.get() < normalOrder.get(),
            "Urgent job must execute before the normal job");
    }

    @Test
    @DisplayName("supportsCancellation() returns true")
    void supportsCancellationReturnsTrue() {
        assertTrue(pool.supportsCancellation());
    }
}
