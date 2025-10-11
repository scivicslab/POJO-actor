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

import com.scivicslab.pojoactor.ActionResult;
import com.scivicslab.pojoactor.CallableByActionName;

/**
 * Sample plugin class for testing dynamic actor loading with string-based invocation.
 *
 * This class demonstrates the actor-WF approach where plugin methods can be invoked
 * using string-based action names, enabling:
 * <ul>
 *   <li>YAML/JSON-based workflow execution</li>
 *   <li>Distributed system support (actions serializable as strings)</li>
 *   <li>No reflection overhead in production</li>
 * </ul>
 *
 * <p>The class provides both traditional type-safe methods and string-based invocation
 * via {@link CallableByActionName}.</p>
 *
 * @author devteam@scivics-lab.com
 * @version 2.0.0
 */
public class MathPlugin implements CallableByActionName {

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

    /**
     * Executes an action by name using string-based arguments.
     *
     * <p>This method enables workflow-driven execution and distributed system support.
     * Actions can be invoked from YAML/JSON workflows or sent across network boundaries.</p>
     *
     * <h3>Supported Actions</h3>
     * <ul>
     *   <li><strong>add</strong>: Args format: "a,b" (e.g., "5,3")</li>
     *   <li><strong>multiply</strong>: Args format: "a,b" (e.g., "4,2")</li>
     *   <li><strong>getLastResult</strong>: No args needed (empty string)</li>
     *   <li><strong>greet</strong>: Args format: "name" (e.g., "World")</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param args string arguments (format depends on action)
     * @return an {@link ActionResult} indicating success or failure
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        try {
            switch (actionName) {
                case "add":
                    String[] addParts = args.split(",");
                    if (addParts.length != 2) {
                        return new ActionResult(false, "add requires 2 arguments: a,b");
                    }
                    int a = Integer.parseInt(addParts[0].trim());
                    int b = Integer.parseInt(addParts[1].trim());
                    int sum = add(a, b);
                    return new ActionResult(true, String.valueOf(sum));

                case "multiply":
                    String[] mulParts = args.split(",");
                    if (mulParts.length != 2) {
                        return new ActionResult(false, "multiply requires 2 arguments: a,b");
                    }
                    int x = Integer.parseInt(mulParts[0].trim());
                    int y = Integer.parseInt(mulParts[1].trim());
                    int product = multiply(x, y);
                    return new ActionResult(true, String.valueOf(product));

                case "getLastResult":
                    int result = getLastResult();
                    return new ActionResult(true, String.valueOf(result));

                case "greet":
                    if (args == null || args.trim().isEmpty()) {
                        return new ActionResult(false, "greet requires a name argument");
                    }
                    String greeting = greet(args.trim());
                    return new ActionResult(true, greeting);

                default:
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (NumberFormatException e) {
            return new ActionResult(false, "Invalid number format: " + e.getMessage());
        } catch (Exception e) {
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }
}
