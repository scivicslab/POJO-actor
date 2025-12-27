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

package com.scivicslab.pojoactor.workflow;

/**
 * Represents a single action in a workflow transition.
 *
 * <p>An action specifies which actor to invoke, which method to call,
 * with what arguments, and how to execute it (direct call vs work-stealing pool).</p>
 *
 * <p>Supports both legacy {@code argument} (String) and new {@code arguments} (String/List/Map) formats:</p>
 * <ul>
 *   <li>{@code argument}: String format for backward compatibility</li>
 *   <li>{@code arguments}: String (single argument), List (multiple arguments), or Map (structured data)</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 * @since 2.7.0
 */
public class Action {

    private String actor;
    private String method;
    private String argument;  // Legacy format
    private Object arguments;  // New format: String, List, or Map
    private ExecutionMode execution = ExecutionMode.POOL;  // Default: pool
    private int poolIndex = 0;

    /**
     * Constructs an empty Action.
     */
    public Action() {
    }

    /**
     * Constructs an Action with specified parameters.
     *
     * @param actor the name of the actor to invoke
     * @param method the method to call
     * @param argument the argument to pass
     */
    public Action(String actor, String method, String argument) {
        this.actor = actor;
        this.method = method;
        this.argument = argument;
    }

    /**
     * Gets the actor name.
     *
     * @return the actor name
     */
    public String getActor() {
        return actor;
    }

    /**
     * Sets the actor name.
     *
     * @param actor the actor name
     */
    public void setActor(String actor) {
        this.actor = actor;
    }

    /**
     * Gets the method name.
     *
     * @return the method name
     */
    public String getMethod() {
        return method;
    }

    /**
     * Sets the method name.
     *
     * @param method the method name
     */
    public void setMethod(String method) {
        this.method = method;
    }

    /**
     * Gets the argument.
     *
     * @return the argument
     */
    public String getArgument() {
        return argument;
    }

    /**
     * Sets the argument.
     *
     * @param argument the argument
     */
    public void setArgument(String argument) {
        this.argument = argument;
    }

    /**
     * Gets the arguments (new format: String, List, or Map).
     *
     * @return the arguments as String, List, or Map, or null if not set
     */
    public Object getArguments() {
        return arguments;
    }

    /**
     * Sets the arguments (new format: String, List, or Map).
     *
     * @param arguments the arguments as String, List, or Map
     */
    public void setArguments(Object arguments) {
        this.arguments = arguments;
    }

    /**
     * Gets the execution mode.
     *
     * @return the execution mode
     */
    public ExecutionMode getExecution() {
        return execution;
    }

    /**
     * Sets the execution mode.
     *
     * @param execution the execution mode
     */
    public void setExecution(ExecutionMode execution) {
        this.execution = execution;
    }

    /**
     * Gets the pool index.
     *
     * @return the pool index
     */
    public int getPoolIndex() {
        return poolIndex;
    }

    /**
     * Sets the pool index.
     *
     * @param poolIndex the pool index (0-based)
     */
    public void setPoolIndex(int poolIndex) {
        this.poolIndex = poolIndex;
    }

    /**
     * Creates an Action from a list representation (legacy format).
     *
     * <p>Converts from old format: [actor, method, argument]</p>
     *
     * @param actionList the list representation
     * @return a new Action instance
     */
    public static Action fromList(java.util.List<String> actionList) {
        if (actionList.size() < 3) {
            throw new IllegalArgumentException("Action list must have at least 3 elements");
        }
        return new Action(actionList.get(0), actionList.get(1), actionList.get(2));
    }

    /**
     * Converts this Action to list representation (for backward compatibility).
     *
     * @return list representation [actor, method, argument]
     */
    public java.util.List<String> toList() {
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(actor);
        list.add(method);
        list.add(argument);
        return list;
    }
}
