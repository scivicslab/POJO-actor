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

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.plugin.MathPlugin;

/**
 * Test class for DynamicActorLoader.
 *
 * Note: This test uses classes from the test classpath directly
 * rather than external JARs for simplicity. The same mechanism
 * works with external JARs loaded via URLClassLoader.
 *
 * @author devteam@scivics-lab.com
 * @version 2.0.0
 */
@DisplayName("DynamicActorLoader tests")
public class DynamicActorLoaderTest {

    private ActorSystem system;

    @BeforeEach
    public void setUp() {
        system = new ActorSystem("testSystem", 4);
    }

    @AfterEach
    public void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    /**
     * Test that a dynamically loaded actor can be created and used.
     */
    @Test
    @DisplayName("Should create actor from dynamically loaded class")
    public void testDynamicActorCreation() throws Exception {
        // In a real scenario, MathPlugin would be loaded from external JAR
        // Here we demonstrate the actor creation pattern
        ActorRef<MathPlugin> mathActor = new ActorRef<>("mathActor", new MathPlugin());

        AtomicInteger result = new AtomicInteger(0);

        // Send message using the actor
        CompletableFuture<Void> future = mathActor.tell(m -> {
            int sum = m.add(5, 3);
            result.set(sum);
        });

        future.get();

        assertEquals(8, result.get(), "Math operation should work correctly");

        mathActor.close();
    }

    /**
     * Test that reflection-based method invocation works with dynamic actors.
     */
    @Test
    @DisplayName("Should invoke methods via reflection on dynamic actor")
    public void testReflectionInvocation() throws Exception {
        ActorRef<Object> mathActor = new ActorRef<>("mathActor", new MathPlugin());

        AtomicReference<String> greeting = new AtomicReference<>();

        // Invoke method via reflection (as would be done with unknown plugin types)
        CompletableFuture<Void> future = mathActor.tell(obj -> {
            try {
                Method greetMethod = obj.getClass().getMethod("greet", String.class);
                String result = (String) greetMethod.invoke(obj, "World");
                greeting.set(result);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        future.get();

        assertEquals("Hello, World from MathPlugin!", greeting.get(),
                     "Reflection invocation should work");

        mathActor.close();
    }

    /**
     * Test that multiple operations can be performed on a dynamic actor.
     */
    @Test
    @DisplayName("Should support multiple operations on dynamic actor")
    public void testMultipleOperations() throws Exception {
        ActorRef<MathPlugin> mathActor = new ActorRef<>("mathActor", new MathPlugin());

        // First operation: add
        CompletableFuture<Integer> addResult = mathActor.ask(m -> m.add(10, 5));
        assertEquals(15, addResult.get(), "Add operation should work");

        // Second operation: multiply
        CompletableFuture<Integer> multiplyResult = mathActor.ask(m -> m.multiply(3, 4));
        assertEquals(12, multiplyResult.get(), "Multiply operation should work");

        // Third operation: get last result
        CompletableFuture<Integer> lastResult = mathActor.ask(m -> m.getLastResult());
        assertEquals(12, lastResult.get(), "Should return last result");

        mathActor.close();
    }

    /**
     * Test that dynamic actor can be registered with ActorSystem.
     */
    @Test
    @DisplayName("Should register dynamic actor with ActorSystem")
    public void testActorSystemIntegration() throws Exception {
        // Create and register dynamic actor using ActorSystem constructor
        ActorRef<MathPlugin> mathActor = new ActorRef<>("mathActor", new MathPlugin(), system);
        system.addActor(mathActor);

        // Verify registration
        assertTrue(system.hasActor("mathActor"), "Actor should be registered");

        // Use the registered actor
        ActorRef<MathPlugin> retrieved = system.getActor("mathActor");
        assertNotNull(retrieved, "Should retrieve registered actor");

        CompletableFuture<Integer> result = retrieved.ask(m -> m.add(7, 3));
        assertEquals(10, result.get(), "Retrieved actor should work correctly");
    }

    /**
     * Test concurrent access to dynamic actor.
     */
    @Test
    @DisplayName("Should handle concurrent access to dynamic actor")
    public void testConcurrentAccess() throws Exception {
        ActorRef<MathPlugin> mathActor = new ActorRef<>("mathActor", new MathPlugin());

        // Send multiple messages concurrently
        CompletableFuture<Integer> f1 = mathActor.ask(m -> m.add(1, 1));
        CompletableFuture<Integer> f2 = mathActor.ask(m -> m.add(2, 2));
        CompletableFuture<Integer> f3 = mathActor.ask(m -> m.add(3, 3));

        // All should complete successfully
        assertEquals(2, f1.get());
        assertEquals(4, f2.get());
        assertEquals(6, f3.get());

        mathActor.close();
    }

    /**
     * Test string-based action invocation using CallableByActionName.
     */
    @Test
    @DisplayName("Should invoke actions using string-based callByActionName")
    public void testStringBasedInvocation() throws Exception {
        ActorRef<MathPlugin> mathActor = new ActorRef<>("mathActor", new MathPlugin());

        // Test add action
        CompletableFuture<ActionResult> addResult = mathActor.ask(m -> m.callByActionName("add", "5,3"));
        ActionResult addOutcome = addResult.get();
        assertTrue(addOutcome.isSuccess(), "add action should succeed");
        assertEquals("8", addOutcome.getResult(), "add should return 8");

        // Test multiply action
        CompletableFuture<ActionResult> multiplyResult = mathActor.ask(m -> m.callByActionName("multiply", "4,2"));
        ActionResult multiplyOutcome = multiplyResult.get();
        assertTrue(multiplyOutcome.isSuccess(), "multiply action should succeed");
        assertEquals("8", multiplyOutcome.getResult(), "multiply should return 8");

        // Test getLastResult action
        CompletableFuture<ActionResult> lastResult = mathActor.ask(m -> m.callByActionName("getLastResult", ""));
        ActionResult lastOutcome = lastResult.get();
        assertTrue(lastOutcome.isSuccess(), "getLastResult action should succeed");
        assertEquals("8", lastOutcome.getResult(), "getLastResult should return 8");

        // Test greet action
        CompletableFuture<ActionResult> greetResult = mathActor.ask(m -> m.callByActionName("greet", "World"));
        ActionResult greetOutcome = greetResult.get();
        assertTrue(greetOutcome.isSuccess(), "greet action should succeed");
        assertEquals("Hello, World from MathPlugin!", greetOutcome.getResult());

        mathActor.close();
    }

    /**
     * Test error handling in string-based invocation.
     */
    @Test
    @DisplayName("Should handle errors in string-based invocation")
    public void testStringBasedInvocationErrors() throws Exception {
        ActorRef<MathPlugin> mathActor = new ActorRef<>("mathActor", new MathPlugin());

        // Test unknown action
        CompletableFuture<ActionResult> unknownResult = mathActor.ask(m -> m.callByActionName("unknown", ""));
        ActionResult unknownOutcome = unknownResult.get();
        assertFalse(unknownOutcome.isSuccess(), "unknown action should fail");
        assertTrue(unknownOutcome.getResult().contains("Unknown action"));

        // Test invalid argument format
        CompletableFuture<ActionResult> invalidResult = mathActor.ask(m -> m.callByActionName("add", "invalid"));
        ActionResult invalidOutcome = invalidResult.get();
        assertFalse(invalidOutcome.isSuccess(), "invalid args should fail");

        // Test missing required argument
        CompletableFuture<ActionResult> missingResult = mathActor.ask(m -> m.callByActionName("greet", ""));
        ActionResult missingOutcome = missingResult.get();
        assertFalse(missingOutcome.isSuccess(), "missing args should fail");

        mathActor.close();
    }

    /**
     * Test workflow-like sequential actions using string-based invocation.
     */
    @Test
    @DisplayName("Should execute workflow-like action sequences")
    public void testWorkflowSequence() throws Exception {
        ActorRef<MathPlugin> mathActor = new ActorRef<>("mathActor", new MathPlugin());

        // Simulate a workflow: add -> multiply -> getLastResult
        // This pattern is common in YAML/JSON workflows

        // Step 1: add 10 + 5 = 15
        mathActor.ask(m -> m.callByActionName("add", "10,5")).get();

        // Step 2: multiply 3 * 4 = 12 (overwrites lastResult)
        mathActor.ask(m -> m.callByActionName("multiply", "3,4")).get();

        // Step 3: get the last result (should be 12)
        ActionResult finalResult = mathActor.ask(m -> m.callByActionName("getLastResult", "")).get();

        assertTrue(finalResult.isSuccess());
        assertEquals("12", finalResult.getResult(), "Should return result of last operation");

        mathActor.close();
    }
}
