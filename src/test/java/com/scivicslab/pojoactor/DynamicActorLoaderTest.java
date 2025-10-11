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

package com.scivicslab.pojoactor;

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
}
