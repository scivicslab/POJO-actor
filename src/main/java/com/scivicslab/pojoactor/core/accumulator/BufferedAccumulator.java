/*
 * Copyright 2025 devteam@scivicslab.com
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An accumulator that buffers results and outputs them on getSummary().
 *
 * <p>This accumulator collects all results in memory and returns them
 * grouped by source when getSummary() is called. This is useful for
 * batch processing and report generation.</p>
 *
 * <h2>Example Output</h2>
 * <pre>
 * === Execution Results ===
 *
 * [node-localhost]
 *   cpu: AMD Ryzen 7 7700 8-Core Processor
 *   gpu: NVIDIA GeForce RTX 4080
 *   memory: 62Gi
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.8.0
 */
public class BufferedAccumulator implements Accumulator {

    private final List<ResultEntry> results = Collections.synchronizedList(new ArrayList<>());

    /**
     * A single result entry.
     *
     * @param source the source identifier
     * @param type the result type
     * @param data the result data
     * @param timestamp when the result was added
     */
    public record ResultEntry(String source, String type, String data, Instant timestamp) {}

    @Override
    public void add(String source, String type, String data) {
        results.add(new ResultEntry(source, type, data, Instant.now()));
    }

    @Override
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Execution Results ===\n");

        // Group by source, maintaining insertion order
        Map<String, List<ResultEntry>> bySource = results.stream()
            .collect(Collectors.groupingBy(
                ResultEntry::source,
                LinkedHashMap::new,
                Collectors.toList()
            ));

        for (Map.Entry<String, List<ResultEntry>> entry : bySource.entrySet()) {
            sb.append(String.format("\n[%s]\n", entry.getKey()));
            for (ResultEntry r : entry.getValue()) {
                sb.append(String.format("  %s: %s\n", r.type(), r.data()));
            }
        }

        return sb.toString();
    }

    @Override
    public int getCount() {
        return results.size();
    }

    @Override
    public void clear() {
        results.clear();
    }

    /**
     * Returns an unmodifiable list of all result entries.
     *
     * @return the list of result entries
     */
    public List<ResultEntry> getResults() {
        return Collections.unmodifiableList(new ArrayList<>(results));
    }
}
