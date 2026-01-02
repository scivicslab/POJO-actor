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

import java.io.PrintStream;

/**
 * An accumulator that outputs results immediately as they are added.
 *
 * <p>This accumulator writes each result to an output stream (default: stdout)
 * as soon as it is added. This is useful for real-time monitoring and debugging.</p>
 *
 * <h2>Example Output</h2>
 * <pre>
 * [node-localhost] cpu: AMD Ryzen 7 7700 8-Core Processor
 * [node-localhost] gpu: NVIDIA GeForce RTX 4080
 * [node-localhost] memory: 62Gi
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.8.0
 */
public class StreamingAccumulator implements Accumulator {

    private final PrintStream output;
    private final String format;
    private int count = 0;

    /**
     * Constructs a StreamingAccumulator with default settings.
     * Outputs to stdout with format "[source] type: data".
     */
    public StreamingAccumulator() {
        this(System.out, "[%s] %s: %s");
    }

    /**
     * Constructs a StreamingAccumulator with custom output and format.
     *
     * @param output the output stream to write to
     * @param format the format string (arguments: source, type, data)
     */
    public StreamingAccumulator(PrintStream output, String format) {
        this.output = output;
        this.format = format;
    }

    @Override
    public void add(String source, String type, String data) {
        output.println(String.format(format, source, type, data));
        count++;
    }

    @Override
    public String getSummary() {
        return String.format("Streaming mode - %d results printed in real-time", count);
    }

    @Override
    public int getCount() {
        return count;
    }

    @Override
    public void clear() {
        count = 0;
    }
}
