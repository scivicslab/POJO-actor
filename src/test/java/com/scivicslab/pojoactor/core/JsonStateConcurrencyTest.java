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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Stress test: two threads writing one JsonState at once lose nothing. The tree is a Jackson
 * ObjectNode, which is not thread-safe, so without a lock concurrent puts overwrite each other's
 * entries in the underlying map.
 */
@Tag("JsonState_Threads_260510_oo01")
@DisplayName("JsonState — concurrent puts from two threads lose nothing")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JsonStateConcurrencyTest {

    private static final int RUNS = 5;
    private static final int PER_THREAD = 20_000;

    @Test
    @DisplayName("two virtual threads put 20 000 distinct keys each: all 40 000 are there")
    void concurrentPutsKeepEveryKey() throws Exception {
        List<String> lossy = new ArrayList<>();
        for (int run = 0; run < RUNS; run++) {
            JsonState state = new JsonState();
            AtomicInteger thrown = new AtomicInteger();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread a = Thread.ofVirtual().start(() -> putKeys(state, "a", barrier, thrown));
            Thread b = Thread.ofVirtual().start(() -> putKeys(state, "b", barrier, thrown));
            a.join();
            b.join();
            if (state.size() != 2 * PER_THREAD || thrown.get() != 0) {
                lossy.add("run" + run + ":size=" + state.size() + ",thrown=" + thrown.get());
            }
        }
        assertEquals(List.of(), lossy, "runs that lost keys: " + lossy.size() + " of " + RUNS);
    }

    private static void putKeys(JsonState state, String prefix, CyclicBarrier barrier, AtomicInteger thrown) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < PER_THREAD; i++) {
            try {
                state.put(prefix + i, i);
            } catch (RuntimeException e) {
                thrown.incrementAndGet();
            }
        }
    }
}
