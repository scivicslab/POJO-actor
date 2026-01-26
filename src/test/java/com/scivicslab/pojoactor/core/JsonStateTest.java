/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.pojoactor.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonState XPath-style accessor.
 */
class JsonStateTest {

    private JsonState state;

    @BeforeEach
    void setUp() {
        state = new JsonState();
    }

    // ========================================================================
    // Basic Read/Write Tests
    // ========================================================================

    @Test
    void put_simpleKey_setsValue() {
        state.put("name", "test");
        assertEquals("test", state.getString("name"));
    }

    @Test
    void put_withDollarPrefix_setsValue() {
        state.put("$.name", "test");
        assertEquals("test", state.getString("$.name"));
        assertEquals("test", state.getString("name"));
    }

    @Test
    void put_nestedPath_createsHierarchy() {
        state.put("workflow.retry", 3);
        assertEquals(3, state.getInt("$.workflow.retry", 0));
    }

    @Test
    void put_deeplyNestedPath_createsFullHierarchy() {
        state.put("a.b.c.d", "deep");
        assertEquals("deep", state.getString("$.a.b.c.d"));
    }

    // ========================================================================
    // Array Access Tests
    // ========================================================================

    @Test
    void put_arrayIndex_createsArray() {
        state.put("hosts[0]", "server1");
        assertEquals("server1", state.getString("$.hosts[0]"));
    }

    @Test
    void put_multipleArrayIndices_expandsArray() {
        state.put("hosts[0]", "server1");
        state.put("hosts[1]", "server2");
        state.put("hosts[2]", "server3");

        assertEquals("server1", state.getString("hosts[0]"));
        assertEquals("server2", state.getString("hosts[1]"));
        assertEquals("server3", state.getString("hosts[2]"));
    }

    @Test
    void put_arrayIndexWithGap_fillsWithNull() {
        state.put("items[2]", "third");
        assertTrue(state.has("items[2]"));
        assertFalse(state.has("items[0]"));
    }

    @Test
    void put_nestedObjectInArray_works() {
        state.put("servers[0].host", "server1.example.com");
        state.put("servers[0].port", 8080);

        assertEquals("server1.example.com", state.getString("servers[0].host"));
        assertEquals(8080, state.getInt("servers[0].port", 0));
    }

    // ========================================================================
    // Type-specific Getter Tests
    // ========================================================================

    @Test
    void getString_existingKey_returnsValue() {
        state.put("name", "Alice");
        assertEquals("Alice", state.getString("name"));
    }

    @Test
    void getString_missingKey_returnsNull() {
        assertNull(state.getString("missing"));
    }

    @Test
    void getString_withDefault_returnsDefaultWhenMissing() {
        assertEquals("default", state.getString("missing", "default"));
    }

    @Test
    void getInt_existingKey_returnsValue() {
        state.put("count", 42);
        assertEquals(42, state.getInt("count", 0));
    }

    @Test
    void getInt_missingKey_returnsDefault() {
        assertEquals(99, state.getInt("missing", 99));
    }

    @Test
    void getLong_existingKey_returnsValue() {
        state.put("timestamp", 1234567890123L);
        assertEquals(1234567890123L, state.getLong("timestamp", 0L));
    }

    @Test
    void getDouble_existingKey_returnsValue() {
        state.put("rate", 3.14159);
        assertEquals(3.14159, state.getDouble("rate", 0.0), 0.00001);
    }

    @Test
    void getBoolean_existingKey_returnsValue() {
        state.put("enabled", true);
        assertTrue(state.getBoolean("enabled", false));
    }

    @Test
    void getBoolean_missingKey_returnsDefault() {
        assertFalse(state.getBoolean("missing", false));
    }

    // ========================================================================
    // Existence Check Tests
    // ========================================================================

    @Test
    void has_existingKey_returnsTrue() {
        state.put("exists", "value");
        assertTrue(state.has("exists"));
    }

    @Test
    void has_missingKey_returnsFalse() {
        assertFalse(state.has("missing"));
    }

    @Test
    void has_nestedPath_checksCorrectly() {
        state.put("a.b.c", "nested");
        assertTrue(state.has("$.a.b.c"));
        assertFalse(state.has("$.a.b.d"));
    }

    @Test
    void has_nullValue_returnsFalse() {
        state.put("nullKey", null);
        assertFalse(state.has("nullKey"));
    }

    // ========================================================================
    // Optional API Tests
    // ========================================================================

    @Test
    void get_existingKey_returnsOptionalWithValue() {
        state.put("data", "value");
        Optional<JsonNode> result = state.get("data");
        assertTrue(result.isPresent());
        assertEquals("value", result.get().asText());
    }

    @Test
    void get_missingKey_returnsEmptyOptional() {
        Optional<JsonNode> result = state.get("missing");
        assertFalse(result.isPresent());
    }

    // ========================================================================
    // Remove Tests
    // ========================================================================

    @Test
    void remove_existingKey_removesValue() {
        state.put("toRemove", "value");
        assertTrue(state.has("toRemove"));

        boolean removed = state.remove("toRemove");
        assertTrue(removed);
        assertFalse(state.has("toRemove"));
    }

    @Test
    void remove_missingKey_returnsFalse() {
        assertFalse(state.remove("missing"));
    }

    @Test
    void remove_nestedKey_removesValue() {
        state.put("parent.child", "value");
        assertTrue(state.has("parent.child"));

        boolean removed = state.remove("parent.child");
        assertTrue(removed);
        assertFalse(state.has("parent.child"));
    }

    // ========================================================================
    // Utility Method Tests
    // ========================================================================

    @Test
    void isEmpty_emptyState_returnsTrue() {
        assertTrue(state.isEmpty());
    }

    @Test
    void isEmpty_nonEmptyState_returnsFalse() {
        state.put("key", "value");
        assertFalse(state.isEmpty());
    }

    @Test
    void size_returnsTopLevelKeyCount() {
        state.put("a", 1);
        state.put("b", 2);
        state.put("c.d", 3);  // c is one top-level key
        assertEquals(3, state.size());
    }

    @Test
    void clear_removesAllValues() {
        state.put("a", 1);
        state.put("b", 2);
        state.clear();
        assertTrue(state.isEmpty());
    }

    @Test
    void copy_createsIndependentCopy() {
        state.put("original", "value");
        JsonState copy = state.copy();

        copy.put("original", "modified");
        assertEquals("value", state.getString("original"));
        assertEquals("modified", copy.getString("original"));
    }

    @Test
    void toString_returnsJsonString() {
        state.put("name", "test");
        String json = state.toString();
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"test\""));
    }

    // ========================================================================
    // Complex Scenario Tests
    // ========================================================================

    @Test
    void workflowScenario_storesAndRetrievesState() {
        // Simulate workflow state management
        state.put("workflow.name", "document-deploy");
        state.put("workflow.retry", 3);
        state.put("workflow.timeout", 300);
        state.put("changedDocs[0]", "doc_SCIVICS001");
        state.put("changedDocs[1]", "doc_SCIVICS002");
        state.put("config.forceBuild", false);

        assertEquals("document-deploy", state.getString("$.workflow.name"));
        assertEquals(3, state.getInt("$.workflow.retry", 0));
        assertEquals("doc_SCIVICS001", state.getString("$.changedDocs[0]"));
        assertEquals("doc_SCIVICS002", state.getString("$.changedDocs[1]"));
        assertFalse(state.getBoolean("$.config.forceBuild", true));
    }

    @Test
    void methodChaining_works() {
        state.put("a", 1)
             .put("b", 2)
             .put("c", 3);

        assertEquals(1, state.getInt("a", 0));
        assertEquals(2, state.getInt("b", 0));
        assertEquals(3, state.getInt("c", 0));
    }
}
