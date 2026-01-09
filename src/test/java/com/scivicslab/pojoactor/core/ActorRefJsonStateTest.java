/*
 * Copyright 2025 devteam@scivics-lab.com
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ActorRef JSON state integration.
 */
class ActorRefJsonStateTest {

    private ActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    // ========================================================================
    // Lazy Initialization Tests
    // ========================================================================

    @Test
    void json_lazyInitialization_createsOnFirstAccess() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        // Initially no JSON state
        assertFalse(actor.hasJsonState());

        // Access creates state
        actor.json().put("key", "value");
        assertTrue(actor.hasJsonState());
    }

    @Test
    void hasJsonState_emptyState_returnsFalse() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        // Access json() but don't put anything
        actor.json();
        assertFalse(actor.hasJsonState());  // Still empty
    }

    // ========================================================================
    // Convenience Method Tests
    // ========================================================================

    @Test
    void putJson_setsValueAndReturnsThis() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        ActorRef<Object> result = actor.putJson("name", "test");
        assertSame(actor, result);  // Returns this for chaining
        assertEquals("test", actor.getJsonString("name"));
    }

    @Test
    void putJson_chainedCalls_work() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        actor.putJson("a", 1)
             .putJson("b", 2)
             .putJson("c", 3);

        assertEquals(1, actor.getJsonInt("a", 0));
        assertEquals(2, actor.getJsonInt("b", 0));
        assertEquals(3, actor.getJsonInt("c", 0));
    }

    @Test
    void getJsonString_existingKey_returnsValue() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        actor.putJson("message", "hello");
        assertEquals("hello", actor.getJsonString("message"));
    }

    @Test
    void getJsonString_missingKey_returnsNull() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        assertNull(actor.getJsonString("missing"));
    }

    @Test
    void getJsonString_withDefault_returnsDefaultWhenMissing() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        assertEquals("default", actor.getJsonString("missing", "default"));
    }

    @Test
    void getJsonInt_existingKey_returnsValue() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        actor.putJson("count", 42);
        assertEquals(42, actor.getJsonInt("count", 0));
    }

    @Test
    void getJsonInt_missingKey_returnsDefault() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        assertEquals(99, actor.getJsonInt("missing", 99));
    }

    @Test
    void getJsonBoolean_existingKey_returnsValue() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        actor.putJson("enabled", true);
        assertTrue(actor.getJsonBoolean("enabled", false));
    }

    @Test
    void hasJson_existingKey_returnsTrue() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        actor.putJson("exists", "value");
        assertTrue(actor.hasJson("exists"));
    }

    @Test
    void hasJson_missingKey_returnsFalse() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        assertFalse(actor.hasJson("missing"));
    }

    @Test
    void clearJsonState_removesAllState() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        actor.putJson("a", 1).putJson("b", 2);
        assertTrue(actor.hasJsonState());

        actor.clearJsonState();
        assertFalse(actor.hasJsonState());
    }

    // ========================================================================
    // XPath-style Path Tests
    // ========================================================================

    @Test
    void putJson_nestedPath_createsHierarchy() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        actor.putJson("workflow.retry", 3);
        assertEquals(3, actor.getJsonInt("$.workflow.retry", 0));
    }

    @Test
    void putJson_arrayPath_createsArray() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor = system.actorOf("actor1", new Object());

        actor.putJson("hosts[0]", "server1");
        actor.putJson("hosts[1]", "server2");

        assertEquals("server1", actor.getJsonString("hosts[0]"));
        assertEquals("server2", actor.getJsonString("hosts[1]"));
    }

    // ========================================================================
    // Actor Independence Tests
    // ========================================================================

    @Test
    void jsonState_perActorIsolation_actorsHaveIndependentState() {
        system = new ActorSystem("test-system");
        ActorRef<Object> actor1 = system.actorOf("actor1", new Object());
        ActorRef<Object> actor2 = system.actorOf("actor2", new Object());

        actor1.putJson("value", 100);
        actor2.putJson("value", 200);

        assertEquals(100, actor1.getJsonInt("value", 0));
        assertEquals(200, actor2.getJsonInt("value", 0));
    }

    // ========================================================================
    // Workflow Scenario Test
    // ========================================================================

    @Test
    void workflowScenario_documentDeployState() {
        system = new ActorSystem("test-system");
        ActorRef<Object> nodeActor = system.actorOf("node-localhost", new Object());

        // Step 1: Detect changes and store in JSON state
        nodeActor.putJson("changedDocs[0]", "doc_SCIVICS001");
        nodeActor.putJson("changedDocs[1]", "doc_SCIVICS002");
        nodeActor.putJson("workflow.status", "detected");
        nodeActor.putJson("workflow.count", 2);

        // Step 2: Clone - check state
        assertEquals("doc_SCIVICS001", nodeActor.getJsonString("changedDocs[0]"));
        assertEquals("doc_SCIVICS002", nodeActor.getJsonString("changedDocs[1]"));
        assertEquals(2, nodeActor.getJsonInt("workflow.count", 0));

        // Step 3: Update status
        nodeActor.putJson("workflow.status", "cloned");
        assertEquals("cloned", nodeActor.getJsonString("workflow.status"));

        // Step 4: Build complete
        nodeActor.putJson("workflow.status", "completed");
        nodeActor.putJson("workflow.success", true);

        assertTrue(nodeActor.getJsonBoolean("workflow.success", false));
    }
}
