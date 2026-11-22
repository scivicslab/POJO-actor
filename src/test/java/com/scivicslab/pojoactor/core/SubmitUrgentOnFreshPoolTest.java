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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies that an urgent task runs even when it is the first work a fresh ManagedThreadPool gets.
 * A ThreadPoolExecutor starts its real threads only from execute(); submitUrgentForActor puts the
 * task at the front of the queue itself, so it must start a real thread too. A task given to a
 * pool that is shut down is refused instead of waiting in a queue that no real thread will read.
 */
@Tag("ManagedThreadPool_260508_oo01")
@DisplayName("ManagedThreadPool — an urgent task on a fresh pool runs, and a shut-down pool refuses one")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SubmitUrgentOnFreshPoolTest {

    @Test
    @DisplayName("submitUrgentForActor() as the first work of a fresh pool runs on one of its real threads")
    void urgentTaskOnFreshPoolRuns() throws Exception {
        int runs = 20;
        int failed = 0;
        for (int i = 0; i < runs; i++) {
            ManagedThreadPool pool = new ManagedThreadPool(2);
            try {
                CountDownLatch ran = new CountDownLatch(1);
                boolean[] onPoolThread = new boolean[1];
                pool.submitUrgentForActor("a", () -> {
                    onPoolThread[0] = pool.ownsCurrentThread();
                    ran.countDown();
                });
                if (!ran.await(1, TimeUnit.SECONDS) || !onPoolThread[0]) {
                    failed++;
                }
            } finally {
                pool.shutdownNow();
            }
        }
        assertEquals(0, failed, "urgent tasks that did not run on a fresh pool: " + failed + " of " + runs);
    }

    @Test
    @DisplayName("submitUrgentForActor() still goes ahead of queued work once the real threads exist")
    void urgentTaskStillRunsFirst() throws Exception {
        ManagedThreadPool pool = new ManagedThreadPool(1);
        try {
            CountDownLatch blocking = new CountDownLatch(1);
            StringBuffer order = new StringBuffer();
            pool.submitForActor("a", () -> {
                try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            pool.submitForActor("a", () -> order.append("normal "));
            pool.submitUrgentForActor("a", () -> order.append("urgent "));
            CountDownLatch done = new CountDownLatch(1);
            pool.submitForActor("a", done::countDown);
            blocking.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("urgent normal ", order.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("submitUrgentForActor() on a shut-down pool throws RejectedExecutionException")
    void shutDownPoolRefusesUrgentTask() {
        ManagedThreadPool pool = new ManagedThreadPool(2);
        pool.shutdown();
        assertThrows(RejectedExecutionException.class, () -> pool.submitUrgentForActor("a", () -> {}));
        assertEquals(0, pool.getPendingJobCountForActor("a"));
    }
}
