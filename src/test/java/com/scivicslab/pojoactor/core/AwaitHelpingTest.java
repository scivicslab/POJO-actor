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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ManagedThreadPool#awaitHelping(CompletableFuture)}: a real thread of the pool
 * that waits for work queued on the same pool runs that work itself, so a pool whose real
 * threads all wait still makes progress.
 */
@Tag("ManagedThreadPool_260508_oo01")
@DisplayName("ManagedThreadPool — awaitHelping runs queued tasks while it waits")
class AwaitHelpingTest {

    private ManagedThreadPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the only real thread waits for a task queued behind it, and runs it")
    void theOnlyRealThreadRunsWhatItWaitsFor() throws Exception {
        pool = new ManagedThreadPool(1);
        CompletableFuture<String> outer = CompletableFuture.supplyAsync(() -> {
            CompletableFuture<String> inner = CompletableFuture.supplyAsync(
                    () -> pool.ownsCurrentThread() ? "on the pool" : "elsewhere", pool);
            try {
                pool.awaitHelping(inner);  // a plain inner.join() here would wait for ever
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return inner.join();
        }, pool);
        assertEquals("on the pool", outer.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("called from a thread outside the pool, it just waits")
    void outsideThePoolItJustWaits() throws Exception {
        pool = new ManagedThreadPool(1);
        CompletableFuture<Integer> f = CompletableFuture.supplyAsync(() -> 42, pool);
        pool.awaitHelping(f);
        assertTrue(f.isDone());
        assertEquals(42, f.get());
    }
}
