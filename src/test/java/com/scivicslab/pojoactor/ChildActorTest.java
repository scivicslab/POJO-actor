package com.scivicslab.pojoactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
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

import com.scivicslab.pojoactor.pojo.Root;

/**
 * Test class for verifying child actor creation and management functionality.
 * This test suite demonstrates the hierarchical actor system capabilities,
 * including parent-child relationships and supervision.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("Creation of child actors")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ChildActorTest {

    private static final Logger logger = Logger.getLogger(EmptyActorSystemTest.class.getName());

    /**
     * Tests the ability to create child actors within an actor system.
     * Verifies that parent actors can create and manage child actors properly.
     */
    @Test
    @Order(1)
    public void should_be_able_to_create_child_actors() {

        // Create a ROOT actor.
        ActorSystem system = new ActorSystem("system1");
        ActorRef<Root> root = system.actorOf("ROOT", new Root());

        // Add child actors to the ROOT actor.
        root.createChild("child01", new ArrayList<Integer>());
        root.createChild("child02", new ArrayList<String>());
        root.createChild("child03", new ArrayList<Double>());

        ActorRef<ArrayList<Integer>> c1 = system.getActor("child01");
        c1.tell((a) -> a.add(1));
        c1.tell((a) -> a.add(2));
        c1.tell((a) -> a.add(3));
        CompletableFuture<String> r1 = c1.ask((a) -> {
            return a.toString();
        });

        ActorRef<ArrayList<String>> c2 = system.getActor("child02");
        c2.tell((a) -> a.add("A"));
        c2.tell((a) -> a.add("B"));
        c2.tell((a) -> a.add("C"));
        CompletableFuture<String> r2 = c2.ask((a) -> {
            return a.toString();
        });

        ActorRef<ArrayList<Double>> c3 = system.getActor("child03");
        c3.tell((a) -> a.add(1.0));
        c3.tell((a) -> a.add(2.0));
        c3.tell((a) -> a.add(3.0));
        CompletableFuture<String> r3 = c3.ask((a) -> {
            return a.toString();
        });

        CompletableFuture<Void> combined = CompletableFuture.allOf(r1, r2, r3);

        String result = null;
        try {
            StringJoiner joiner = new StringJoiner("\n");
            combined.get(3, TimeUnit.SECONDS);

            joiner.add(r1.get());
            joiner.add(r2.get());
            joiner.add(r3.get());

            result = joiner.toString() + "\n";
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
        }
        system.terminate();

        // --- Expectations ---
        String expectation = """
                [1, 2, 3]
                [A, B, C]
                [1.0, 2.0, 3.0]
                """;

        assertEquals(expectation, result);

    }

    @Test
    @Order(2)
    public void should_be_able_to_list_actors() {

        ActorSystem system = new ActorSystem("system1");
        ActorRef<Root> root = system.actorOf("ROOT", new Root());
        ActorRef<ArrayList<Integer>> child01 = root.createChild("child01", new ArrayList<Integer>());
        root.createChild("child02", new ArrayList<String>());
        root.createChild("child03", new ArrayList<Double>());

        child01.createChild("child01-01", new ArrayList<Integer>());


        List<String> names = system.listActorNames();
        String result = names.toString();

        system.terminate();

        //logger.info(result);
        // --- Expectations ---
        String[] expectedNames = {
            "ROOT", "child01", "child02",
            "child03", "child01-01"
        };

        for (String n: expectedNames) {
            assertTrue(names.contains(n));
        }
        
    }


    @Test
    @Order(3)
    public void should_be_able_to_get_actor_by_name() {

        ActorSystem system = new ActorSystem("system1");
        ActorRef<Root> root = system.actorOf("ROOT", new Root());
        ActorRef<ArrayList<Integer>> child01 = root.createChild("child01", new ArrayList<Integer>());
        root.createChild("child02", new ArrayList<String>());
        root.createChild("child03", new ArrayList<Double>());

        child01.createChild("child01-01", new ArrayList<Integer>());


        ActorRef<ArrayList<Integer>> c = system.getActor("child01-01");
        
        // --- Expectations ---
        assertEquals("child01-01", c.getName());


        c.tell((a) -> a.add(1));
        c.tell((a) -> a.add(2));
        c.tell((a) -> a.add(3));
        CompletableFuture<String> r1 = c.ask((a) -> {
            return a.toString();
        });


        String result = null;
        try {
            result = r1.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
        }
        String expectation="[1, 2, 3]";
        logger.info(result);
        assertEquals(expectation, result);
        system.terminate();        
    }

}
