package com.scivicslab.pojoactor.core;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.pojo.MatrixCalculator;

import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test class for verifying thread pool functionality within the ActorSystem.
 * This test suite demonstrates how actors can utilize multiple thread pools
 * for CPU-intensive and parallel computing tasks.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
@DisplayName("Using the ThreadPool in an ActorSystem")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ThreadPoolTest {

    private static final Logger logger = Logger.getLogger(ThreadPoolTest.class.getName());

    /**
     * Tests parallel computation of a large matrix multiplication using block decomposition.
     * Demonstrates how a single large matrix multiplication can be divided into blocks
     * and computed in parallel by multiple actors in the ActorSystem.
     */
    @Test
    @Order(1)
    public void should_run_parallel_large_matrix_multiplication_in_ThreadPool() {

        // Create an ActorSystem with a 4 parallel ThreadPool.
        ActorSystem system = new ActorSystem("system1", 4);

        // Create large matrices for multiplication: A(400x400) * B(400x400) = C(400x400)
        final int matrixSize = 400;
        final int blockSize = 100; // Each actor handles a 100x100 block
        final int blocksPerDimension = matrixSize / blockSize; // 4x4 = 16 blocks total
        
        RandomGenerator rng = new MersenneTwister();
        
        // Initialize the large matrices
        double[][] matrixA = new double[matrixSize][matrixSize];
        double[][] matrixB = new double[matrixSize][matrixSize];
        
        // Fill matrices with random values
        for (int i = 0; i < matrixSize; i++) {
            for (int j = 0; j < matrixSize; j++) {
                matrixA[i][j] = rng.nextDouble() * 10.0;
                matrixB[i][j] = rng.nextDouble() * 10.0;
            }
        }

        ArrayList<CompletableFuture<Double>> futures = new ArrayList<>();
        
        try {
            int actorId = 0;
            // Create actors for each block of the result matrix
            for (int blockRow = 0; blockRow < blocksPerDimension; blockRow++) {
                for (int blockCol = 0; blockCol < blocksPerDimension; blockCol++) {
                    MatrixCalculator calculator = new MatrixCalculator();
                    String actorName = String.format("block_actor_%d_%d", blockRow, blockCol);
                    system.actorOf(actorName, calculator);
                    ActorRef<MatrixCalculator> actor = system.getActor(actorName);

                    // Calculate block boundaries
                    final int startRow = blockRow * blockSize;
                    final int endRow = startRow + blockSize;
                    final int startCol = blockCol * blockSize;
                    final int endCol = startCol + blockSize;

                    // Initialize the actor with its specific block coordinates
                    actor.tell((a) -> {
                        a.initBlock(matrixA, matrixB, startRow, endRow, startCol, endCol, matrixSize);
                    }, system.getManagedThreadPool()).get();

                    // Start the block calculation
                    CompletableFuture<Double> blockSum = actor.ask((a) -> {
                        return a.getBlockSum(); // Calculate and sum the block
                    }, system.getManagedThreadPool());

                    futures.add(blockSum);
                    actorId++;
                }
            }

            // Wait for all block calculations to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).get();
            
            logger.log(Level.INFO, String.format("Completed parallel matrix multiplication using %d actors", 
                                                blocksPerDimension * blocksPerDimension));
            
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, e.getMessage());
        }

        // Verify we created the expected number of block calculations
        assertEquals(blocksPerDimension * blocksPerDimension, futures.size());

        // Verify all block calculations completed successfully
        double totalSum = 0.0;
        for (CompletableFuture<Double> blockSum : futures) {
            try {
                double sum = blockSum.get();
                assertTrue(sum >= 0.0); // Matrix multiplication result should be positive
                totalSum += sum;
            } catch (InterruptedException | ExecutionException e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
        }
        
        logger.log(Level.INFO, String.format("Total sum of result matrix elements: %.2f", totalSum));
        assertTrue(totalSum > 0.0);
    }

}
