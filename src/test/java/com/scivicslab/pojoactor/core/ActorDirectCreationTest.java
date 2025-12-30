package com.scivicslab.pojoactor.core;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test class for verifying actor creation and operation without an ActorSystem.
 * This test suite demonstrates that actors can be created and used directly
 * without being managed by an ActorSystem instance.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("Running actors without actorsystem")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ActorDirectCreationTest {

    private static final Logger logger = Logger.getLogger(ActorDirectCreationTest.class.getName());


    /** Actors can be created directly, without using ActorSystem.
     * 
     * <ul>
     * <li>Given a POJO (Plain Old Java Object)</li>
     * <li>When you call a constructor of {@code ActorRef} class</li>
     * <li>Then you will get an {@code ActorRef} object corresponding to the given POJO</li>
     * </ul>
    */
    @DisplayName("Should be able to create actors directly, without using ActorSystem")
    @Test
    @Order(1)
    public void testActorCreation() {

        ActorRef<ArrayList<Integer>> arrayActor = new ActorRef<>("arrayActor", new ArrayList<Integer>());

        arrayActor.tell((a)->a.add(1));
        arrayActor.tell((a)->a.add(2));
        arrayActor.tell((a)->a.add(3));
        CompletableFuture<String> f = (CompletableFuture<String>) arrayActor.ask((a) -> a.toString());

        String result = "";
        try {
            result = f.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, "", e);
        }

        arrayActor.close();

        // ----- Expectation -----
        String expectation = """
            [1, 2, 3]""";

        // Check the answer.
        assertEquals(expectation, result);

    }


    @DisplayName("Should be able to process messages in the order they are sent")
    @Test
    @Order(2)
    public void testOrderOfMessageProcessing() {

        ActorRef<ArrayList<Integer>> arrayActor = new ActorRef<>("arrayActor", new ArrayList<Integer>());

        Stream.iterate(0, n->{return n<10;}, n->{return ++n;})
            .forEach(n->arrayActor.tell((a)->{
                        randomSleep(); // Sleep for a random period of time between 0 and 1 second.
                        a.add(n);
                        }));

        CompletableFuture<String> f = (CompletableFuture<String>)arrayActor.ask((a)->a.toString());

        String result = "";
        try {
            result = f.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
        }

        arrayActor.close();

        // ----- Expectation -----
        ArrayList<Integer> expList = new ArrayList<>();
        for (int i=0; i<10; i++) {
            expList.add(i);
        }
        String expectation = expList.toString();
 
        // Check the answer.
       assertEquals(expectation, result);

    }


    public static void randomSleep() {
        try {
            double sec = Math.random();
            Thread.sleep((int)Math.floor(sec * 1000));
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    public void testActorTermination() {
            
            ActorRef<ArrayList<Integer>> arrayActor = new ActorRef<>("arrayActor", new ArrayList<Integer>());
    
            arrayActor.tell((a)->a.add(1));
            arrayActor.tell((a)->a.add(2));
            arrayActor.tell((a)->a.add(3));
            CompletableFuture<String> f = (CompletableFuture<String>) arrayActor.ask((a) -> a.toString());
    
            String result = "";
            try {
                result = f.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                logger.log(Level.SEVERE, "", e);
            }
    
            arrayActor.close();
    
            // ----- Expectation -----
            String expectation = """
                [1, 2, 3]""";
    
            // Check the answer.
            assertEquals(expectation, result);
    
            // ----- Expectation -----
            boolean exp = false;
    
            // Check the answer.
            assertEquals(exp, arrayActor.isAlive());
            
    }

}
