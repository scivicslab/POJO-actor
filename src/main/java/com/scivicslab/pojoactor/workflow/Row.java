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

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single row in the workflow matrix.
 *
 * <p>Each row defines a state transition with associated actions. The states
 * list typically contains two elements: the current state and the next state.
 * The actions can be represented in two ways:</p>
 * <ul>
 *   <li>Legacy format: List of string lists [actorName, actionName, argument]</li>
 *   <li>New format: List of Action objects with execution mode support</li>
 * </ul>
 *
 * <p>This class is designed to be populated from YAML, JSON, or XML workflow definitions
 * using deserialization frameworks like SnakeYAML or Jackson.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class Row {

    List<String> states;
    String vertexName;  // Optional identifier for overlay matching
    List<Action> actions;  // Unified format for all workflow types

    /**
     * Returns the list of Action objects for this row.
     *
     * @return a list of Action objects
     */
    public List<Action> getActions() {
        return this.actions;
    }

    /**
     * Returns the list of states for this row.
     *
     * <p>Typically contains two elements: [currentState, nextState]</p>
     *
     * @return a list of state identifiers
     */
    public List<String> getStates() {
        return this.states;
    }

    /**
     * Sets the list of actions for this row.
     *
     * @param actions a list of Action objects
     */
    public void setActions(List<Action> actions) {
        this.actions = actions;
    }

    /**
     * Sets the list of states for this row.
     *
     * @param list a list of state identifiers
     */
    public void setStates(List<String> list) {
        this.states = list;
    }

    /**
     * Returns the vertex name for this row.
     *
     * <p>The vertex name is used as a stable identifier for overlay matching.
     * When applying patches, vertices are matched by this name rather than
     * by states or array index.</p>
     *
     * @return the vertex name, or null if not set
     * @since 2.9.0
     */
    public String getVertexName() {
        return this.vertexName;
    }

    /**
     * Sets the vertex name for this row.
     *
     * @param vertexName the vertex name identifier
     * @since 2.9.0
     */
    public void setVertexName(String vertexName) {
        this.vertexName = vertexName;
    }
}
