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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test class for verifying ActorSystem functionality.
 * This test suite covers actor system creation, lifecycle management,
 * and actor registration/retrieval operations.
 *
 * @author devteam@scivicslab.com
 * @version 2.7.0
 */
@DisplayName("ActorSystem functionality tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ActorSystemTest {

    private static final Logger logger = Logger.getLogger(ActorSystemTest.class.getName());

    /**
     * Example 1: Create an empty ActorSystem.
     *
     * Situation: Creating an actor system for the first time
     * Expected: System is created and in alive state
     */
    @DisplayName("Should create empty ActorSystem")
    @Test
    @Order(1)
    public void testEmptyActorSystemCreation() {
        ActorSystem system = new ActorSystem("mySystem");

        // Verify system name is in toString output
        String result = system.toString();
        assertTrue(result.contains("mySystem"), "toString should contain system name");

        // Verify system is alive
        assertTrue(system.isAlive(), "System should be alive after creation");

        // Verify no actors are registered
        List<String> actors = system.listActorNames();
        assertTrue(actors.isEmpty(), "Actor list should be empty");

        // Terminate and verify
        system.terminate();
        assertFalse(system.isAlive(), "System should not be alive after termination");
    }

    /**
     * Example 2: Create actors with actorOf().
     *
     * Situation: Creating actors using the ActorSystem
     * Expected: Actors are created and can receive messages
     */
    @DisplayName("Should create actors with actorOf()")
    @Test
    @Order(2)
    public void testActorOfCreation() throws InterruptedException, ExecutionException, TimeoutException {
        ActorSystem system = new ActorSystem("system1");

        // Create actor using actorOf
        ActorRef<ArrayList<Integer>> actor = system.actorOf("counter", new ArrayList<Integer>());

        // Send messages
        actor.tell((ArrayList<Integer> list) -> list.add(1));
        actor.tell((ArrayList<Integer> list) -> list.add(2));
        actor.tell((ArrayList<Integer> list) -> list.add(3));

        // Get result
        CompletableFuture<String> future = actor.ask((ArrayList<Integer> list) -> list.toString());
        String result = future.get(3, TimeUnit.SECONDS);

        // Verify result
        assertEquals("[1, 2, 3]", result, "Messages should be processed in order");

        // Verify actor is registered
        assertTrue(system.hasActor("counter"), "Actor should be registered in system");

        // Verify actor can be retrieved
        ActorRef<ArrayList<Integer>> retrieved = system.getActor("counter");
        assertNotNull(retrieved, "Retrieved actor should not be null");
        assertEquals(actor, retrieved, "Retrieved actor should be the same instance");

        system.terminate();
    }

    /**
     * Example 3: Add directly created actors.
     *
     * Situation: Adding actors that were created directly (without ActorSystem)
     * Expected: Actors are added to the system and can be used
     */
    @DisplayName("Should add directly created actors")
    @Test
    @Order(3)
    public void testAddDirectlyCreatedActors() throws InterruptedException, ExecutionException, TimeoutException {
        // Create actors directly
        ActorRef<ArrayList<Integer>> actor1 = new ActorRef<>("actor1", new ArrayList<Integer>());
        ActorRef<ArrayList<Double>> actor2 = new ActorRef<>("actor2", new ArrayList<Double>());

        // Create ActorSystem
        ActorSystem system = new ActorSystem("system1");

        // Add actors to system
        system.addActor(actor1);
        system.addActor(actor2);

        // Verify actors are registered
        assertTrue(system.hasActor("actor1"), "actor1 should be registered");
        assertTrue(system.hasActor("actor2"), "actor2 should be registered");

        // Use actor through system
        ActorRef<ArrayList<Integer>> a1 = system.getActor("actor1");
        a1.tell((ArrayList<Integer> list) -> list.add(100));
        CompletableFuture<String> future = a1.ask((ArrayList<Integer> list) -> list.toString());
        String result = future.get(3, TimeUnit.SECONDS);

        assertEquals("[100]", result, "Actor should process messages correctly");

        // Terminate and verify actors are closed
        system.terminate();
        assertFalse(actor1.isAlive(), "actor1 should be closed after system termination");
        assertFalse(actor2.isAlive(), "actor2 should be closed after system termination");
    }

    /**
     * Example 4: Builder pattern for system creation.
     *
     * Situation: Creating ActorSystem with custom configuration
     * Expected: System is created with custom settings
     */
    @DisplayName("Should create ActorSystem with Builder pattern")
    @Test
    @Order(4)
    public void testBuilderPatternCreation() {
        ActorSystem system = new ActorSystem.Builder("customSystem")
            .threadNum(8)
            .build();

        // Verify system name
        String name = system.toString();
        assertTrue(name.contains("customSystem"), "System name should be set");

        // Verify system is alive
        assertTrue(system.isAlive(), "System should be alive");

        system.terminate();
        assertFalse(system.isAlive(), "System should not be alive after termination");
    }

    /**
     * Example 5: Remove actors from system.
     *
     * Situation: Removing an actor that is no longer needed
     * Expected: Actor is removed from the system
     */
    @DisplayName("Should remove actors from system")
    @Test
    @Order(5)
    public void testRemoveActor() {
        ActorSystem system = new ActorSystem("system1");
        ActorRef<String> actor = system.actorOf("tempActor", "data");

        // Verify actor exists
        assertTrue(system.hasActor("tempActor"), "Actor should exist before removal");

        // Remove actor
        system.removeActor("tempActor");

        // Verify actor is removed
        assertFalse(system.hasActor("tempActor"), "Actor should not exist after removal");
        assertNull(system.getActor("tempActor"), "getActor should return null for removed actor");

        system.terminate();
    }

    /**
     * Example 6: List all actor names.
     *
     * Situation: Getting a list of all actors in the system
     * Expected: All registered actor names are returned
     */
    @DisplayName("Should list all actor names")
    @Test
    @Order(6)
    public void testListActorNames() {
        ActorSystem system = new ActorSystem("system1");

        // Create multiple actors
        system.actorOf("actor1", "data1");
        system.actorOf("actor2", "data2");
        system.actorOf("actor3", "data3");

        // Get actor names
        List<String> actorNames = system.listActorNames();

        // Verify all actors are listed
        assertEquals(3, actorNames.size(), "Should have 3 actors");
        assertTrue(actorNames.contains("actor1"), "Should contain actor1");
        assertTrue(actorNames.contains("actor2"), "Should contain actor2");
        assertTrue(actorNames.contains("actor3"), "Should contain actor3");

        system.terminate();
    }
}
