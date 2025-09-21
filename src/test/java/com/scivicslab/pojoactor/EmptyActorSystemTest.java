package com.scivicslab.pojoactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test class for verifying basic ActorSystem construction and lifecycle operations.
 * This test suite ensures that empty ActorSystems can be created, managed, and terminated properly.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("Construction of an ActorSystem")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EmptyActorSystemTest {

    private static final Logger logger = Logger.getLogger(EmptyActorSystemTest.class.getName());

    /** You can create an empty ActorSystem object. */
    @DisplayName("Should be able to create empty ActorSystem")
    @Test
    @Order(1)
    public void testEmptyActorSystemCreation() {

        ActorSystem system = new ActorSystem("system1");
        String result = system.toString() + "\n";
        String expectations = "{actorSystem: \"system1\"}\n";
        assertEquals(expectations, result);
        system.terminate();
    }

    /**
     * Creates actors that belong to an `ActorSystem`.
     * 
     * In majority of existing actor frameworks, actors are registered as child
     * actors of the root actor.
     * On the other hand, the POJO-actor framework does not force this manner for
     * simplicity, and you can choose the way that actors are registered directly in an
     * `ActorSystem` or as descendents of the root actor.
     * 
     * <ul>
     * <li>Given a POJO (Plain Old Java Object) and an `ActorSystem` object</li>
     * <li>When you call `ActorSystem.actorOf` method</li>
     * <li>Then you will get an `ActorRef` object corresponding to the given POJO on
     * the given `ActorSystem` object.</li>
     * </ul>
     * 
     */
    @DisplayName("Should be able to create actors as a member of an ActorSystem")
    @Test
    @Order(2)
    public void testActorOf() {
        ActorSystem system = new ActorSystem("system1");
        ActorRef<ArrayList<Integer>> actor1 = system.actorOf("arraylist_actor01", new ArrayList<Integer>());
        ActorRef<ArrayList<String>> actor2 = system.actorOf("arraylist_actor02", new ArrayList<String>());

        actor1.tell((a) -> a.add(1));
        actor1.tell((a) -> a.add(2));
        actor1.tell((a) -> a.add(3));

        actor2.tell((a) -> a.add("A"));
        actor2.tell((a) -> a.add("B"));
        actor2.tell((a) -> a.add("C"));

        CompletableFuture<String> f1 = (CompletableFuture<String>) actor1.ask((a) -> a.toString());
        CompletableFuture<String> f2 = (CompletableFuture<String>) actor2.ask((a) -> a.toString());
        CompletableFuture<Void> combined = CompletableFuture.allOf(f1, f2);

        String result = null;
        try {
            StringJoiner joiner = new StringJoiner("\n");
            combined.get();

            joiner.add(f1.get(3, TimeUnit.SECONDS));
            joiner.add(f2.get(3, TimeUnit.SECONDS));

            result = joiner.toString();
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
        }

        // --- Expectations ---
        String expectation = """
                [1, 2, 3]
                [A, B, C]""";

        assertEquals(expectation, result);
        system.terminate();

    }

    
    @DisplayName("Should be able to add directly created actors to an ActorSystem")
    @Test
    @Order(2)
    public void testAddActor() {

        // 1. Create actors directly (without using ActorSystem)
        ActorRef<ArrayList<Integer>> actor1 = new ActorRef<>("actor1", new ArrayList<Integer>());
        ActorRef<ArrayList<Double>> actor2 = new ActorRef<>("actor2", new ArrayList<Double>());

        // 2. Create an empty ActorSystem.
        ActorSystem system = new ActorSystem("system1");

        // 3. Add the actors to the ActorSystem.
        // The name of the actor is automatically copied to ActorSystem as well.
        system.addActor(actor1); 
        system.addActor(actor2);

        // 4. Get the actors from the ActorSystem.
        ActorRef<ArrayList<Integer>> a1 = system.getActor("actor1");
        ActorRef<ArrayList<Double>> a2 = system.getActor("actor2");

        // 5. Send messages to the actors.
        a1.tell((a)->a.add(1));
        a1.tell((a)->a.add(2));
        a1.tell((a)->a.add(3));

        a2.tell((a)->a.add(1.0));
        a2.tell((a)->a.add(2.0));
        a2.tell((a)->a.add(3.0));

        // 6. Ask the actors to return their states.
        CompletableFuture<String> f1 = (CompletableFuture<String>) a1.ask((a) -> a.toString());
        CompletableFuture<String> f2 = (CompletableFuture<String>) a2.ask((a) -> a.toString());

        // 7. Wait for the completion of the Futures.
        CompletableFuture<Void> combined = CompletableFuture.allOf(f1, f2);
        try {
            combined.get(3, TimeUnit.SECONDS);
            logger.info(f1.get());
            logger.info(f2.get());

            // 8. Check the results.
            assertEquals("[1, 2, 3]", f1.get());
            assertEquals("[1.0, 2.0, 3.0]", f2.get());
            
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
        }

        system.terminate();

        assertFalse(system.getActor("actor1").isAlive());
        assertFalse(system.getActor("actor2").isAlive());
    }

    
}
