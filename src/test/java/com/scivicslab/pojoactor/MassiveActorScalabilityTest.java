package com.scivicslab.pojoactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test class for verifying POJO-actor's ability to handle thousands of actors
 * using virtual threads while controlling CPU core usage through work-stealing pools.
 * This demonstrates the scalability advantages over traditional thread-per-actor systems.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("Massive Actor Scalability Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MassiveActorScalabilityTest {

    private static final Logger logger = Logger.getLogger(MassiveActorScalabilityTest.class.getName());

    /**
     * Simple counter POJO for testing actor scalability
     */
    static class Counter {
        private int count = 0;
        
        public void increment() {
            count++;
        }
        
        public int getValue() {
            return count;
        }
        
        public void reset() {
            count = 0;
        }
    }

    /**
     * Tests the creation and operation of 5,000 actors simultaneously.
     * Demonstrates that POJO-actor can handle thousands of virtual thread actors
     * while using only a limited number of CPU cores for computation.
     */
    @Test
    @Order(1)
    public void should_handle_5000_actors_with_virtual_threads() {
        final int actorCount = 5000;
        
        // Create actor system with only 4 CPU threads for computation
        ActorSystem system = new ActorSystem("massiveSystem", 4);
        List<ActorRef<Counter>> actors = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Create 5,000 actors - this would be impossible with real threads
            for (int i = 0; i < actorCount; i++) {
                ActorRef<Counter> actor = system.actorOf("counter" + i, new Counter());
                actors.add(actor);
            }
            
            long creationTime = System.currentTimeMillis();
            logger.log(Level.INFO, String.format("Created %d actors in %d ms", 
                                                actorCount, creationTime - startTime));
            
            // Send increment messages to all actors concurrently
            List<CompletableFuture<Void>> incrementFutures = new ArrayList<>();
            for (ActorRef<Counter> actor : actors) {
                incrementFutures.add(actor.tell(c -> c.increment()));
            }
            
            // Wait for all increments to complete
            CompletableFuture.allOf(incrementFutures.toArray(new CompletableFuture[0])).get();
            
            long incrementTime = System.currentTimeMillis();
            logger.log(Level.INFO, String.format("Processed increment messages for %d actors in %d ms", 
                                                actorCount, incrementTime - creationTime));
            
            // Verify all actors have been incremented
            List<CompletableFuture<Integer>> valueFutures = new ArrayList<>();
            for (ActorRef<Counter> actor : actors) {
                valueFutures.add(actor.ask(c -> c.getValue()));
            }
            
            // Check all results
            for (CompletableFuture<Integer> future : valueFutures) {
                int value = future.get();
                assertEquals(1, value, "Each counter should have been incremented once");
            }
            
            long verificationTime = System.currentTimeMillis();
            logger.log(Level.INFO, String.format("Verified all %d actors in %d ms", 
                                                actorCount, verificationTime - incrementTime));
            
            // Test multiple operations per actor
            List<CompletableFuture<Void>> multiFutures = new ArrayList<>();
            for (ActorRef<Counter> actor : actors) {
                multiFutures.add(actor.tell(c -> {
                    c.increment();
                    c.increment(); 
                    c.increment();
                }));
            }
            
            CompletableFuture.allOf(multiFutures.toArray(new CompletableFuture[0])).get();
            
            // Verify final values
            for (ActorRef<Counter> actor : actors) {
                int finalValue = actor.ask(c -> c.getValue()).get();
                assertEquals(4, finalValue, "Each counter should have value 4 after multiple increments");
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            logger.log(Level.INFO, String.format("Total test completed in %d ms for %d actors", 
                                                totalTime, actorCount));
            
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "Exception during massive actor test", e);
            throw new RuntimeException(e);
        } finally {
            system.terminate();
        }
        
        // Verify we created the expected number of actors
        assertEquals(actorCount, actors.size());
        assertTrue(actors.size() >= 1000, "Should handle at least 1000 actors easily");
        
        logger.log(Level.INFO, String.format("Successfully demonstrated scalability with %d virtual thread actors", actorCount));
    }

    /**
     * Tests that the system can handle rapid creation and destruction of many actors
     * without resource exhaustion.
     */
    @Test
    @Order(2)
    public void should_handle_rapid_actor_lifecycle() {
        final int rounds = 10;
        final int actorsPerRound = 1000;
        
        ActorSystem system = new ActorSystem("lifecycleSystem", 2);
        
        try {
            for (int round = 0; round < rounds; round++) {
                List<ActorRef<Counter>> roundActors = new ArrayList<>();
                
                // Create actors for this round
                for (int i = 0; i < actorsPerRound; i++) {
                    ActorRef<Counter> actor = system.actorOf("round" + round + "_actor" + i, new Counter());
                    roundActors.add(actor);
                }
                
                // Use the actors
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (ActorRef<Counter> actor : roundActors) {
                    futures.add(actor.tell(c -> c.increment()));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
                
                // Verify and clean up
                for (ActorRef<Counter> actor : roundActors) {
                    int value = actor.ask(c -> c.getValue()).get();
                    assertEquals(1, value);
                    actor.close(); // Explicitly close actors
                }
                
                logger.log(Level.INFO, String.format("Completed round %d with %d actors", 
                                                    round + 1, actorsPerRound));
            }
            
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "Exception during lifecycle test", e);
            throw new RuntimeException(e);
        } finally {
            system.terminate();
        }
        
        logger.log(Level.INFO, String.format("Successfully handled %d rounds of %d actors each", 
                                            rounds, actorsPerRound));
    }
}