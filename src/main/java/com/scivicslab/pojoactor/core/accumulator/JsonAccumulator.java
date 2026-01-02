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

import org.json.JSONObject;

/**
 * An accumulator that outputs results in JSON format.
 *
 * <p>This accumulator organizes results as a JSON object where keys are
 * source identifiers and values are objects containing type-data pairs.
 * This is useful for API responses, log storage, and external system integration.</p>
 *
 * <h2>Example Output</h2>
 * <pre>
 * {
 *   "node-localhost": {
 *     "cpu": "AMD Ryzen 7 7700 8-Core Processor",
 *     "gpu": "NVIDIA GeForce RTX 4080",
 *     "memory": "62Gi"
 *   }
 * }
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.8.0
 */
public class JsonAccumulator implements Accumulator {

    private final JSONObject results = new JSONObject();
    private int count = 0;

    @Override
    public synchronized void add(String source, String type, String data) {
        if (!results.has(source)) {
            results.put(source, new JSONObject());
        }
        results.getJSONObject(source).put(type, data);
        count++;
    }

    @Override
    public String getSummary() {
        return results.toString(2);  // Pretty print with 2-space indent
    }

    @Override
    public int getCount() {
        return count;
    }

    @Override
    public synchronized void clear() {
        // Remove all keys from the JSONObject
        for (String key : results.keySet().toArray(new String[0])) {
            results.remove(key);
        }
        count = 0;
    }

    /**
     * Returns the results as a JSONObject.
     *
     * <p>Note: This returns a copy to prevent external modification.</p>
     *
     * @return a copy of the results JSONObject
     */
    public JSONObject getResultsAsJson() {
        return new JSONObject(results.toString());
    }
}
