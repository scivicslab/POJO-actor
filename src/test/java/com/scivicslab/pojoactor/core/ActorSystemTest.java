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
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
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
@Tag("S_base")
@DisplayName("ActorSystem — actor lifecycle management (S1)")
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

    /**
     * Example 7: Any POJO can become an actor without modification.
     *
     * Situation: Turning standard library objects (ArrayList, HashMap) into actors
     * Expected: Standard library classes work as actors with full message ordering
     */
    @DisplayName("Should turn any POJO into an actor without modification")
    @Test
    @Order(7)
    public void testAnyPojoCanBecomeActor() throws InterruptedException, ExecutionException, TimeoutException {
        ActorSystem system = new ActorSystem("listSystem");

        // Standard ArrayList becomes an actor — no subclassing, no annotations
        ActorRef<ArrayList<String>> listActor = system.actorOf("myList", new ArrayList<String>());

        listActor.tell(list -> list.add("Hello"));
        listActor.tell(list -> list.add("World"));
        listActor.tell(list -> list.add("from"));
        listActor.tell(list -> list.add("POJO-actor"));

        int size = listActor.ask(list -> list.size()).get(3, TimeUnit.SECONDS);
        assertEquals(4, size, "ArrayList actor should contain 4 elements");

        String first = listActor.ask(list -> list.get(0)).get(3, TimeUnit.SECONDS);
        assertEquals("Hello", first, "First element should be Hello");

        String joined = listActor.ask(list -> String.join(" ", list)).get(3, TimeUnit.SECONDS);
        assertEquals("Hello World from POJO-actor", joined);

        // Standard HashMap becomes an actor — same pattern
        ActorRef<HashMap<String, Integer>> mapActor = system.actorOf("myMap", new HashMap<>());
        mapActor.tell(map -> map.put("a", 1));
        mapActor.tell(map -> map.put("b", 2));

        int val = mapActor.ask(map -> map.get("a")).get(3, TimeUnit.SECONDS);
        assertEquals(1, val, "HashMap actor should return stored value");

        system.terminate();
    }

    /**
     * Example 8: Massive actor scalability with virtual threads.
     *
     * Situation: Creating a large number of actors simultaneously
     * Expected: Thousands of actors can be created and process messages without exhausting threads
     */
    @DisplayName("Should handle massive actor scalability with virtual threads")
    @Test
    @Order(8)
    public void testMassiveActorScalability() throws InterruptedException, ExecutionException, TimeoutException {
        final int actorCount = 1000;
        ActorSystem system = new ActorSystem("massiveSystem", 4);
        List<ActorRef<int[]>> actors = new ArrayList<>();

        // Create 1,000 actors — virtual threads make this lightweight
        for (int i = 0; i < actorCount; i++) {
            actors.add(system.actorOf("counter" + i, new int[]{0}));
        }

        assertEquals(actorCount, system.listActorNames().size(), "All actors should be registered");

        // Send one message to each actor concurrently
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ActorRef<int[]> actor : actors) {
            futures.add(actor.tell(c -> c[0]++));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

        // Verify all actors processed their message
        for (ActorRef<int[]> actor : actors) {
            int value = actor.ask(c -> c[0]).get(3, TimeUnit.SECONDS);
            assertEquals(1, value, "Each actor should have processed exactly one message");
        }

        system.terminate();
    }

    /**
     * Example 9: removeActor stops the thread and cleans up the parent's children set.
     *
     * Situation: A parent actor has a child; the child is removed.
     * Expected: The child's virtual thread stops, it is gone from the system, and its name
     * is gone from the parent's NamesOfChildren -- not just from the system's registry.
     */
    @DisplayName("Should stop the thread and unlink from the parent on removeActor")
    @Test
    @Order(9)
    public void testRemoveActorStopsThreadAndUnlinksParent() {
        ActorSystem system = new ActorSystem("system1");
        ActorRef<String> parent = system.actorOf("parent", "p");
        ActorRef<String> child = parent.createChild("child", "c");

        assertTrue(parent.getNamesOfChildren().contains("child"), "Parent should list the child before removal");
        assertTrue(child.isAlive(), "Child should be alive before removal");

        system.removeActor("child");

        assertFalse(system.hasActor("child"), "Child should be gone from the system registry");
        assertFalse(child.isAlive(), "Child's virtual thread should have stopped");
        assertFalse(parent.getNamesOfChildren().contains("child"), "Parent should no longer list the child");

        // Calling removeActor again on an already-removed name is a no-op, not an error.
        system.removeActor("child");

        system.terminate();
    }

    /**
     * Example 10: removeOffsprings removes every descendant, leaf before parent, but not the
     * actor itself.
     *
     * Situation: An actor has a child, and that child has its own child (a grandchild).
     * Expected: removeOffsprings on the top actor stops and removes both the child and the
     * grandchild, empties the top actor's children set, and leaves the top actor itself alive.
     */
    @DisplayName("Should remove every descendant leaf-first, leaving the actor itself alone")
    @Test
    @Order(10)
    public void testRemoveOffsprings() {
        ActorSystem system = new ActorSystem("system1");
        ActorRef<String> root = system.actorOf("root", "r");
        ActorRef<String> child = root.createChild("child", "c");
        ActorRef<String> grandchild = child.createChild("grandchild", "g");

        system.removeOffsprings("root");

        assertTrue(root.isAlive(), "The actor removeOffsprings was called on should stay alive");
        assertTrue(system.hasActor("root"), "root itself should remain in the registry");
        assertTrue(root.getNamesOfChildren().isEmpty(), "root's children set should end up empty");

        assertFalse(child.isAlive(), "child's virtual thread should have stopped");
        assertFalse(system.hasActor("child"), "child should be gone from the system registry");
        assertFalse(grandchild.isAlive(), "grandchild's virtual thread should have stopped");
        assertFalse(system.hasActor("grandchild"), "grandchild should be gone from the system registry");

        system.terminate();
    }

    /**
     * Example 11: removeActorRecursively removes the whole subtree, including the actor
     * named.
     *
     * Situation: An actor has a child, and that child has its own child (a grandchild).
     * Expected: removeActorRecursively on the top actor stops and removes the top actor
     * itself as well as the child and the grandchild, and unlinks the top actor from its own
     * parent.
     */
    @DisplayName("Should remove the actor itself along with every descendant")
    @Test
    @Order(11)
    public void testRemoveActorRecursively() {
        ActorSystem system = new ActorSystem("system1");
        ActorRef<String> top = system.actorOf("top", "t");
        ActorRef<String> root = top.createChild("root", "r");
        ActorRef<String> child = root.createChild("child", "c");
        ActorRef<String> grandchild = child.createChild("grandchild", "g");

        system.removeActorRecursively("root");

        assertFalse(root.isAlive(), "root's virtual thread should have stopped");
        assertFalse(system.hasActor("root"), "root should be gone from the system registry");
        assertFalse(top.getNamesOfChildren().contains("root"), "top should no longer list root as a child");

        assertFalse(child.isAlive(), "child's virtual thread should have stopped");
        assertFalse(system.hasActor("child"), "child should be gone from the system registry");
        assertFalse(grandchild.isAlive(), "grandchild's virtual thread should have stopped");
        assertFalse(system.hasActor("grandchild"), "grandchild should be gone from the system registry");

        assertTrue(top.isAlive(), "top itself should stay alive -- it was not the subtree root removed");

        system.terminate();
    }
}
