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

package com.scivicslab.pojoactor.plugin;

/**
 * Sample plugin class for testing dynamic actor loading.
 *
 * This class demonstrates that any POJO can be loaded as an actor
 * from an external JAR file at runtime.
 *
 * @author devteam@scivics-lab.com
 * @version 2.0.0
 */
public class MathPlugin {

    private int lastResult = 0;

    /**
     * Public no-argument constructor required for dynamic loading.
     */
    public MathPlugin() {
        // Required for Class.newInstance()
    }

    /**
     * Adds two numbers and stores the result.
     *
     * @param a first number
     * @param b second number
     * @return the sum
     */
    public int add(int a, int b) {
        lastResult = a + b;
        return lastResult;
    }

    /**
     * Multiplies two numbers and stores the result.
     *
     * @param a first number
     * @param b second number
     * @return the product
     */
    public int multiply(int a, int b) {
        lastResult = a * b;
        return lastResult;
    }

    /**
     * Returns the last calculated result.
     *
     * @return the last result
     */
    public int getLastResult() {
        return lastResult;
    }

    /**
     * Returns a greeting message.
     *
     * @param name the name to greet
     * @return greeting message
     */
    public String greet(String name) {
        return "Hello, " + name + " from MathPlugin!";
    }
}
