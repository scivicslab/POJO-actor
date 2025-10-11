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

import java.util.List;

/**
 * Represents a single row in the workflow matrix.
 *
 * <p>Each row defines a state transition with associated actions. The states
 * list typically contains two elements: the current state and the next state.
 * The actions list contains action specifications, where each action is
 * represented as a list of strings: [actorName, actionName, argument].</p>
 *
 * <p>This class is designed to be populated from YAML or JSON workflow definitions
 * using deserialization frameworks like SnakeYAML or Jackson.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class Row {

    List<String> states;
    List<List<String>> actions;

    /**
     * Returns the list of actions for this row.
     *
     * <p>Each action is a list of strings containing:
     * [actorName, actionName, argument]</p>
     *
     * @return a list of action specifications
     */
    public List<List<String>> getActions() {
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
     * @param list a list of action specifications
     */
    public void setActions(List<List<String>> list) {
        this.actions = list;
    }


    /**
     * Sets the list of states for this row.
     *
     * @param list a list of state identifiers
     */
    public void setStates(List<String> list) {
        this.states = list;
    }


}
