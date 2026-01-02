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

package com.scivicslab.pojoactor.core.accumulator;

/**
 * Interface for accumulating results from multiple sources.
 *
 * <p>An Accumulator collects data from multiple actors (typically child actors)
 * and aggregates them for later retrieval. This is useful in distributed
 * workflows where results from multiple nodes need to be collected and
 * presented together.</p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * // Create an accumulator
 * Accumulator acc = new StreamingAccumulator();
 *
 * // Add results from different sources
 * acc.add("node-1", "cpu", "Intel Xeon E5-2680");
 * acc.add("node-1", "memory", "64GB");
 * acc.add("node-2", "cpu", "AMD EPYC 7542");
 * acc.add("node-2", "memory", "128GB");
 *
 * // Get the summary
 * String summary = acc.getSummary();
 * }</pre>
 *
 * <h2>Standard Implementations</h2>
 * <ul>
 *   <li>{@link StreamingAccumulator} - Outputs results immediately as they arrive</li>
 *   <li>{@link BufferedAccumulator} - Buffers results and outputs on getSummary()</li>
 *   <li>{@link TableAccumulator} - Formats results as a table</li>
 *   <li>{@link JsonAccumulator} - Outputs results in JSON format</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 * @since 2.8.0
 */
public interface Accumulator {

    /**
     * Adds a result to this accumulator.
     *
     * @param source the source identifier (e.g., actor name like "node-localhost")
     * @param type the type of result (e.g., "cpu", "memory", "error")
     * @param data the result data as a string
     */
    void add(String source, String type, String data);

    /**
     * Returns a formatted summary of all accumulated results.
     *
     * <p>The format of the summary depends on the implementation.
     * For example, {@link StreamingAccumulator} returns a simple count,
     * while {@link BufferedAccumulator} returns all results grouped by source.</p>
     *
     * @return the formatted summary string
     */
    String getSummary();

    /**
     * Clears all accumulated results.
     *
     * <p>After calling this method, the accumulator should be in its initial state,
     * as if no results had been added.</p>
     */
    default void clear() {
        // Default implementation does nothing
    }

    /**
     * Returns the number of results that have been added.
     *
     * @return the count of added results
     */
    default int getCount() {
        return 0;
    }
}
