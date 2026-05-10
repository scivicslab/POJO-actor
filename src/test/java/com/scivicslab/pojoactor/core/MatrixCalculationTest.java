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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies state S1.03: CPU-intensive work is delegated to ManagedThreadPool
 * while virtual-thread actors remain responsive to light messages.
 *
 * Pattern: light init via tell() → heavy compute via ask(pool)
 */
@Tag("S_base.03")
@DisplayName("MatrixCalculation — parallel compute via ManagedThreadPool (S1.03)")
public class MatrixCalculationTest {

    static class MatrixBlock {
        private double[][] matrixA;
        private double[][] matrixB;
        private int startRow;
        private int startCol;
        private int blockSize;
        private int matrixSize;

        void initBlock(double[][] a, double[][] b, int blockRow, int blockCol, int blockSize) {
            this.matrixA = a;
            this.matrixB = b;
            this.startRow = blockRow * blockSize;
            this.startCol = blockCol * blockSize;
            this.blockSize = blockSize;
            this.matrixSize = a.length;
        }

        double calculateBlockSum() {
            double sum = 0;
            for (int i = startRow; i < startRow + blockSize; i++) {
                for (int j = startCol; j < startCol + blockSize; j++) {
                    for (int k = 0; k < matrixSize; k++) {
                        sum += matrixA[i][k] * matrixB[k][j];
                    }
                }
            }
            return sum;
        }
    }

    private ActorSystem system;
    private ManagedThreadPool pool;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("matrixSystem", 4);
        pool = (ManagedThreadPool) system.getManagedThreadPool();
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    /**
     * Example 1: Parallel matrix multiplication using block decomposition.
     *
     * Situation: A large matrix multiplication is divided into blocks,
     *            each handled by a separate actor.
     * Expected: Light init uses virtual threads; heavy compute uses ManagedThreadPool.
     *           All block results are correct and computed in parallel.
     */
    @Test
    @DisplayName("Should compute matrix blocks in parallel via ManagedThreadPool")
    public void testParallelMatrixMultiplication() throws Exception {
        final int matrixSize = 200;
        final int blockSize = 100;
        final int blocksPerSide = matrixSize / blockSize; // 2×2 = 4 blocks

        // Fill matrices with 1.0 — result[i][j] = matrixSize for all cells
        double[][] matrixA = new double[matrixSize][matrixSize];
        double[][] matrixB = new double[matrixSize][matrixSize];
        for (int i = 0; i < matrixSize; i++)
            for (int j = 0; j < matrixSize; j++) {
                matrixA[i][j] = 1.0;
                matrixB[i][j] = 1.0;
            }

        List<CompletableFuture<Double>> futures = new ArrayList<>();

        for (int blockRow = 0; blockRow < blocksPerSide; blockRow++) {
            for (int blockCol = 0; blockCol < blocksPerSide; blockCol++) {
                ActorRef<MatrixBlock> actor = system.actorOf(
                    String.format("block_%d_%d", blockRow, blockCol),
                    new MatrixBlock());

                final int r = blockRow, c = blockCol;

                // Light operation: set references — virtual thread is sufficient
                actor.tell(b -> b.initBlock(matrixA, matrixB, r, c, blockSize))
                     .get(2, TimeUnit.SECONDS);

                // Heavy computation: matrix multiply — delegate to ManagedThreadPool
                futures.add(actor.ask(b -> b.calculateBlockSum(), pool));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

        // Each block: blockSize × blockSize cells, each cell = matrixSize (sum of 1×1 over matrixSize terms)
        double expectedBlockSum = (double) blockSize * blockSize * matrixSize;
        for (CompletableFuture<Double> f : futures) {
            assertEquals(expectedBlockSum, f.get(), 0.001, "Block sum should match expected value");
        }
    }

    /**
     * Example 2: Actor remains responsive to light messages during heavy computation.
     *
     * Situation: Heavy computation is running in ManagedThreadPool.
     * Expected: The actor can still accept light tell() messages on virtual threads
     *           without being blocked by the heavy computation.
     */
    @Test
    @DisplayName("Should remain responsive to light messages during heavy computation")
    public void testActorResponsiveDuringHeavyComputation() throws Exception {
        final int matrixSize = 200;
        final int blockSize = 200;

        double[][] matrixA = new double[matrixSize][matrixSize];
        double[][] matrixB = new double[matrixSize][matrixSize];
        for (int i = 0; i < matrixSize; i++)
            for (int j = 0; j < matrixSize; j++) {
                matrixA[i][j] = 1.0;
                matrixB[i][j] = 1.0;
            }

        ActorRef<MatrixBlock> actor = system.actorOf("heavyBlock", new MatrixBlock());

        // Light init via virtual thread
        actor.tell(b -> b.initBlock(matrixA, matrixB, 0, 0, blockSize)).get(2, TimeUnit.SECONDS);

        // Heavy computation in pool (non-blocking for the actor's virtual thread)
        CompletableFuture<Double> heavyResult = actor.ask(b -> b.calculateBlockSum(), pool);

        // Light message can be processed via virtual thread even while pool job is running
        long lightStart = System.currentTimeMillis();
        int[] statusHolder = {0};
        actor.tellNow(b -> statusHolder[0] = 1).get(1, TimeUnit.SECONDS);
        long lightElapsed = System.currentTimeMillis() - lightStart;

        assertTrue(statusHolder[0] == 1, "Light message should have been processed");
        assertTrue(lightElapsed < 500, "Light message should not be blocked by heavy computation");

        // Wait for heavy computation
        double result = heavyResult.get(10, TimeUnit.SECONDS);
        double expected = (double) matrixSize * matrixSize * matrixSize;
        assertEquals(expected, result, 0.001);
    }
}
