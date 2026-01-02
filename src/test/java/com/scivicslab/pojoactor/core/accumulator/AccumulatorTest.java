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
package com.scivicslab.pojoactor.core.accumulator;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for Accumulator implementations.
 *
 * <p>Accumulators are POJOs that collect results from multiple sources
 * and provide formatted summaries. These tests verify all standard
 * implementations following the Specification by Example approach.</p>
 *
 * @author devteam@scivics-lab.com
 * @since 2.8.0
 */
@DisplayName("Accumulator Specification by Example")
public class AccumulatorTest {

    // ========================================================================
    // Part 1: StreamingAccumulator Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 1: StreamingAccumulator")
    class StreamingAccumulatorTests {

        /**
         * Example 1: Streaming output to stdout.
         */
        @Test
        @DisplayName("Should output immediately when add() is called")
        public void testImmediateOutput() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            StreamingAccumulator acc = new StreamingAccumulator(ps, "[%s] %s: %s");

            acc.add("node-1", "cpu", "Intel Xeon");

            String output = baos.toString();
            assertTrue(output.contains("[node-1] cpu: Intel Xeon"),
                "Should output in format [source] type: data");
        }

        /**
         * Example 2: Multiple results output in order.
         */
        @Test
        @DisplayName("Should output multiple results in order")
        public void testMultipleOutputs() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            StreamingAccumulator acc = new StreamingAccumulator(ps, "[%s] %s: %s");

            acc.add("node-1", "cpu", "Intel Xeon");
            acc.add("node-1", "memory", "64GB");
            acc.add("node-2", "cpu", "AMD EPYC");

            String output = baos.toString();
            assertTrue(output.contains("Intel Xeon"));
            assertTrue(output.contains("64GB"));
            assertTrue(output.contains("AMD EPYC"));
            assertEquals(3, acc.getCount());
        }

        /**
         * Example 3: getSummary returns count information.
         */
        @Test
        @DisplayName("getSummary should return count of printed results")
        public void testGetSummary() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            StreamingAccumulator acc = new StreamingAccumulator(ps, "[%s] %s: %s");

            acc.add("node-1", "cpu", "test");
            acc.add("node-2", "cpu", "test");

            String summary = acc.getSummary();
            assertTrue(summary.contains("2"),
                "Summary should mention count of results");
            assertTrue(summary.toLowerCase().contains("streaming"),
                "Summary should indicate streaming mode");
        }

        /**
         * Example 4: clear resets the count.
         */
        @Test
        @DisplayName("clear should reset the count")
        public void testClear() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            StreamingAccumulator acc = new StreamingAccumulator(ps, "[%s] %s: %s");

            acc.add("node-1", "cpu", "test");
            acc.add("node-2", "cpu", "test");
            assertEquals(2, acc.getCount());

            acc.clear();
            assertEquals(0, acc.getCount());
        }

        /**
         * Example 5: Default constructor uses stdout.
         */
        @Test
        @DisplayName("Default constructor should work without errors")
        public void testDefaultConstructor() {
            StreamingAccumulator acc = new StreamingAccumulator();
            assertDoesNotThrow(() -> acc.add("test", "type", "data"));
            assertEquals(1, acc.getCount());
        }
    }

    // ========================================================================
    // Part 2: BufferedAccumulator Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 2: BufferedAccumulator")
    class BufferedAccumulatorTests {

        /**
         * Example 6: Results are buffered, not immediately output.
         */
        @Test
        @DisplayName("Should buffer results until getSummary is called")
        public void testBuffering() {
            BufferedAccumulator acc = new BufferedAccumulator();

            acc.add("node-1", "cpu", "Intel Xeon");
            acc.add("node-1", "memory", "64GB");

            assertEquals(2, acc.getCount());
            assertEquals(2, acc.getResults().size());
        }

        /**
         * Example 7: getSummary groups results by source.
         */
        @Test
        @DisplayName("getSummary should group results by source")
        public void testGroupBySource() {
            BufferedAccumulator acc = new BufferedAccumulator();

            acc.add("node-1", "cpu", "Intel Xeon");
            acc.add("node-2", "cpu", "AMD EPYC");
            acc.add("node-1", "memory", "64GB");

            String summary = acc.getSummary();
            assertTrue(summary.contains("[node-1]"));
            assertTrue(summary.contains("[node-2]"));
            assertTrue(summary.contains("Intel Xeon"));
            assertTrue(summary.contains("AMD EPYC"));
            assertTrue(summary.contains("64GB"));
        }

        /**
         * Example 8: getSummary includes header.
         */
        @Test
        @DisplayName("getSummary should include header")
        public void testSummaryHeader() {
            BufferedAccumulator acc = new BufferedAccumulator();
            acc.add("node-1", "test", "data");

            String summary = acc.getSummary();
            assertTrue(summary.contains("Execution Results"),
                "Summary should have a header");
        }

        /**
         * Example 9: clear removes all buffered results.
         */
        @Test
        @DisplayName("clear should remove all buffered results")
        public void testClear() {
            BufferedAccumulator acc = new BufferedAccumulator();
            acc.add("node-1", "cpu", "test");
            acc.add("node-2", "cpu", "test");
            assertEquals(2, acc.getCount());

            acc.clear();
            assertEquals(0, acc.getCount());
            assertTrue(acc.getResults().isEmpty());
        }

        /**
         * Example 10: getResults returns unmodifiable list.
         */
        @Test
        @DisplayName("getResults should return unmodifiable list")
        public void testUnmodifiableResults() {
            BufferedAccumulator acc = new BufferedAccumulator();
            acc.add("node-1", "cpu", "test");

            assertThrows(UnsupportedOperationException.class, () -> {
                acc.getResults().clear();
            });
        }
    }

    // ========================================================================
    // Part 3: TableAccumulator Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 3: TableAccumulator")
    class TableAccumulatorTests {

        /**
         * Example 11: Table format with header.
         */
        @Test
        @DisplayName("getSummary should produce table with header row")
        public void testTableHeader() {
            TableAccumulator acc = new TableAccumulator();
            acc.add("node-1", "cpu", "Intel Xeon");
            acc.add("node-1", "memory", "64GB");

            String summary = acc.getSummary();
            assertTrue(summary.contains("Node"),
                "Table should have Node column header");
            assertTrue(summary.contains("cpu"),
                "Table should have cpu column header");
            assertTrue(summary.contains("memory"),
                "Table should have memory column header");
        }

        /**
         * Example 12: Table format with multiple rows.
         */
        @Test
        @DisplayName("getSummary should show each source as a row")
        public void testTableRows() {
            TableAccumulator acc = new TableAccumulator();
            acc.add("node-1", "cpu", "Intel");
            acc.add("node-2", "cpu", "AMD");
            acc.add("node-1", "memory", "64GB");
            acc.add("node-2", "memory", "128GB");

            String summary = acc.getSummary();
            assertTrue(summary.contains("node-1"));
            assertTrue(summary.contains("node-2"));
            assertTrue(summary.contains("Intel"));
            assertTrue(summary.contains("AMD"));
        }

        /**
         * Example 13: Table with separator line.
         */
        @Test
        @DisplayName("Table should have separator line")
        public void testTableSeparator() {
            TableAccumulator acc = new TableAccumulator();
            acc.add("node-1", "cpu", "test");

            String summary = acc.getSummary();
            assertTrue(summary.contains("---"),
                "Table should have separator line");
        }

        /**
         * Example 14: Truncation of long values.
         */
        @Test
        @DisplayName("Long values should be truncated")
        public void testTruncation() {
            TableAccumulator acc = new TableAccumulator(20); // 20 char column width
            acc.add("node-1", "cpu", "This is a very long CPU name that exceeds column width");

            String summary = acc.getSummary();
            assertTrue(summary.contains("..."),
                "Long values should be truncated with ...");
        }

        /**
         * Example 15: Empty table returns "No results".
         */
        @Test
        @DisplayName("Empty accumulator should return 'No results'")
        public void testEmptyTable() {
            TableAccumulator acc = new TableAccumulator();

            String summary = acc.getSummary();
            assertEquals("No results", summary);
        }

        /**
         * Example 16: clear removes all entries.
         */
        @Test
        @DisplayName("clear should remove all table entries")
        public void testClear() {
            TableAccumulator acc = new TableAccumulator();
            acc.add("node-1", "cpu", "test");
            acc.add("node-2", "cpu", "test");
            assertEquals(2, acc.getCount());

            acc.clear();
            assertEquals(0, acc.getCount());
            assertEquals("No results", acc.getSummary());
        }
    }

    // ========================================================================
    // Part 4: JsonAccumulator Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 4: JsonAccumulator")
    class JsonAccumulatorTests {

        /**
         * Example 17: JSON format output.
         */
        @Test
        @DisplayName("getSummary should return valid JSON")
        public void testJsonFormat() {
            JsonAccumulator acc = new JsonAccumulator();
            acc.add("node-1", "cpu", "Intel Xeon");

            String summary = acc.getSummary();
            assertTrue(summary.contains("{"));
            assertTrue(summary.contains("}"));
            assertTrue(summary.contains("\"node-1\""));
            assertTrue(summary.contains("\"cpu\""));
            assertTrue(summary.contains("\"Intel Xeon\""));
        }

        /**
         * Example 18: JSON nested structure.
         */
        @Test
        @DisplayName("JSON should nest type-data under source")
        public void testJsonNesting() {
            JsonAccumulator acc = new JsonAccumulator();
            acc.add("node-1", "cpu", "Intel");
            acc.add("node-1", "memory", "64GB");

            String summary = acc.getSummary();
            // Should be: { "node-1": { "cpu": "Intel", "memory": "64GB" } }
            assertTrue(summary.contains("\"cpu\""));
            assertTrue(summary.contains("\"memory\""));
        }

        /**
         * Example 19: Multiple sources in JSON.
         */
        @Test
        @DisplayName("JSON should include all sources")
        public void testMultipleSources() {
            JsonAccumulator acc = new JsonAccumulator();
            acc.add("node-1", "cpu", "Intel");
            acc.add("node-2", "cpu", "AMD");

            String summary = acc.getSummary();
            assertTrue(summary.contains("\"node-1\""));
            assertTrue(summary.contains("\"node-2\""));
        }

        /**
         * Example 20: getResultsAsJson returns copy.
         */
        @Test
        @DisplayName("getResultsAsJson should return independent copy")
        public void testGetResultsAsJson() {
            JsonAccumulator acc = new JsonAccumulator();
            acc.add("node-1", "cpu", "Intel");

            var json1 = acc.getResultsAsJson();
            var json2 = acc.getResultsAsJson();

            assertNotSame(json1, json2, "Should return new copy each time");
            assertEquals(json1.toString(), json2.toString());
        }

        /**
         * Example 21: clear removes all JSON data.
         */
        @Test
        @DisplayName("clear should remove all JSON data")
        public void testClear() {
            JsonAccumulator acc = new JsonAccumulator();
            acc.add("node-1", "cpu", "test");
            assertEquals(1, acc.getCount());

            acc.clear();
            assertEquals(0, acc.getCount());
            assertEquals("{}", acc.getSummary().replaceAll("\\s", ""));
        }

        /**
         * Example 22: Pretty print with indentation.
         */
        @Test
        @DisplayName("JSON output should be pretty printed with nested structure")
        public void testPrettyPrint() {
            JsonAccumulator acc = new JsonAccumulator();
            acc.add("node-1", "cpu", "Intel");
            acc.add("node-1", "memory", "64GB");

            String summary = acc.getSummary();
            // With nested structure, pretty print should have newlines
            assertTrue(summary.contains("\n") || summary.contains("  "),
                "Pretty print should have newlines or indentation");
        }
    }

    // ========================================================================
    // Part 5: Common Behavior Tests
    // ========================================================================

    @Nested
    @DisplayName("Part 5: Common Behavior")
    class CommonBehaviorTests {

        /**
         * Example 23: All implementations handle empty source.
         */
        @Test
        @DisplayName("Should handle empty source name")
        public void testEmptySource() {
            StreamingAccumulator streaming = new StreamingAccumulator();
            BufferedAccumulator buffered = new BufferedAccumulator();
            TableAccumulator table = new TableAccumulator();
            JsonAccumulator json = new JsonAccumulator();

            assertDoesNotThrow(() -> {
                streaming.add("", "type", "data");
                buffered.add("", "type", "data");
                table.add("", "type", "data");
                json.add("", "type", "data");
            });
        }

        /**
         * Example 24: All implementations handle empty data.
         */
        @Test
        @DisplayName("Should handle empty data value")
        public void testEmptyData() {
            BufferedAccumulator acc = new BufferedAccumulator();
            acc.add("source", "type", "");

            assertEquals(1, acc.getCount());
            assertTrue(acc.getSummary().contains("type"));
        }

        /**
         * Example 25: Unicode support.
         */
        @Test
        @DisplayName("Should handle Unicode characters")
        public void testUnicodeSupport() {
            BufferedAccumulator acc = new BufferedAccumulator();
            acc.add("ノード1", "CPU", "日本語テスト");

            String summary = acc.getSummary();
            assertTrue(summary.contains("ノード1"));
            assertTrue(summary.contains("日本語テスト"));
        }

        /**
         * Example 26: Thread safety (basic check).
         */
        @Test
        @DisplayName("Should handle concurrent adds without error")
        public void testBasicThreadSafety() throws InterruptedException {
            BufferedAccumulator acc = new BufferedAccumulator();

            Thread t1 = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    acc.add("thread1", "count", String.valueOf(i));
                }
            });

            Thread t2 = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    acc.add("thread2", "count", String.valueOf(i));
                }
            });

            t1.start();
            t2.start();
            t1.join();
            t2.join();

            assertEquals(200, acc.getCount(),
                "All adds should be counted");
        }
    }

    // ========================================================================
    // Part 6: Accumulator Interface Default Methods
    // ========================================================================

    @Nested
    @DisplayName("Part 6: Interface Default Methods")
    class InterfaceDefaultMethodTests {

        /**
         * Example 27: Default getCount returns 0.
         */
        @Test
        @DisplayName("Default getCount should return 0")
        public void testDefaultGetCount() {
            // Create minimal implementation to test default
            Accumulator minimal = new Accumulator() {
                @Override
                public void add(String source, String type, String data) {}

                @Override
                public String getSummary() { return ""; }
            };

            assertEquals(0, minimal.getCount());
        }

        /**
         * Example 28: Default clear does nothing.
         */
        @Test
        @DisplayName("Default clear should not throw")
        public void testDefaultClear() {
            Accumulator minimal = new Accumulator() {
                @Override
                public void add(String source, String type, String data) {}

                @Override
                public String getSummary() { return ""; }
            };

            assertDoesNotThrow(() -> minimal.clear());
        }
    }
}
