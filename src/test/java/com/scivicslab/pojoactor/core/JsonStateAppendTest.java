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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies JsonState.append: it adds to the end of a list, makes a list of one where the path
 * holds nothing, refuses a path that holds something else, and two threads appending at once
 * keep every value.
 */
@Tag("JsonState_Threads_260510_oo01")
@DisplayName("JsonState — append adds to the end of a list, atomically")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JsonStateAppendTest {

    @Test
    @DisplayName("append makes a list of one, then adds to its end and answers the index")
    void appendAddsToTheEnd() {
        JsonState state = new JsonState();
        assertEquals(0, state.append("items", "alpha"));
        assertEquals(1, state.append("items", 2));
        assertEquals("alpha", state.getString("items[0]"));
        assertEquals(2, state.getInt("items[1]", -1));
        assertEquals(2, state.select("items").size());
    }

    @Test
    @DisplayName("append refuses a path that holds a value that is not a list")
    void appendRefusesANonList() {
        JsonState state = new JsonState();
        state.put("name", "x");
        assertThrows(IllegalStateException.class, () -> state.append("name", "y"));
        assertEquals("x", state.getString("name"));
    }

    @Test
    @DisplayName("two virtual threads append 20 000 values each: all 40 000 are there, each once")
    void concurrentAppendsKeepEveryValue() throws Exception {
        JsonState state = new JsonState();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread a = Thread.ofVirtual().start(() -> appendValues(state, "a", barrier));
        Thread b = Thread.ofVirtual().start(() -> appendValues(state, "b", barrier));
        a.join();
        b.join();
        Set<String> seen = new HashSet<>();
        state.select("items").forEach(node -> seen.add(node.asText()));
        assertEquals(40_000, state.select("items").size());
        assertEquals(40_000, seen.size());
    }

    private static void appendValues(JsonState state, String prefix, CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < 20_000; i++) {
            state.append("items", prefix + i);
        }
    }
}
