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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An accumulator that formats results as a table.
 *
 * <p>This accumulator organizes results into a table format where rows are
 * sources and columns are result types. This is useful for comparing
 * results across multiple nodes.</p>
 *
 * <h2>Example Output</h2>
 * <pre>
 * Node                | cpu                           | gpu                           | memory
 * ----------------------------------------------------------------------------------------------------
 * node-web-01         | Intel Xeon E5-2680 v4         | No NVIDIA GPU                 | 64Gi
 * node-web-02         | Intel Xeon E5-2680 v4         | No NVIDIA GPU                 | 64Gi
 * node-db-01          | AMD EPYC 7542                 | NVIDIA A100                   | 256Gi
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.8.0
 */
public class TableAccumulator implements Accumulator {

    private final Map<String, Map<String, String>> table =
        Collections.synchronizedMap(new LinkedHashMap<>());
    private final int columnWidth;

    /**
     * Constructs a TableAccumulator with default column width of 30 characters.
     */
    public TableAccumulator() {
        this(30);
    }

    /**
     * Constructs a TableAccumulator with custom column width.
     *
     * @param columnWidth the width of each column in characters
     */
    public TableAccumulator(int columnWidth) {
        this.columnWidth = columnWidth;
    }

    @Override
    public void add(String source, String type, String data) {
        table.computeIfAbsent(source, (String k) -> new LinkedHashMap<>())
             .put(type, data);
    }

    @Override
    public String getSummary() {
        if (table.isEmpty()) {
            return "No results";
        }

        // Collect all column names
        Set<String> columns = table.values().stream()
            .flatMap((Map<String, String> m) -> m.keySet().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        StringBuilder sb = new StringBuilder();
        int nodeWidth = 20;

        // Header row
        sb.append(String.format("%-" + nodeWidth + "s", "Node"));
        for (String col : columns) {
            sb.append(String.format("| %-" + columnWidth + "s", col));
        }
        sb.append("\n");

        // Separator line
        sb.append("-".repeat(nodeWidth + columns.size() * (columnWidth + 2))).append("\n");

        // Data rows
        for (Map.Entry<String, Map<String, String>> row : table.entrySet()) {
            sb.append(String.format("%-" + nodeWidth + "s", truncate(row.getKey(), nodeWidth)));
            for (String col : columns) {
                String value = row.getValue().getOrDefault(col, "-");
                sb.append(String.format("| %-" + columnWidth + "s", truncate(value, columnWidth)));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s.length() > maxLen) {
            return s.substring(0, maxLen - 3) + "...";
        }
        return s;
    }

    @Override
    public int getCount() {
        return table.values().stream().mapToInt(Map::size).sum();
    }

    @Override
    public void clear() {
        table.clear();
    }
}
