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
 * Represents a matrix-based workflow definition.
 *
 * <p>A MatrixCode contains a name and a list of {@link Transition} objects that define
 * the workflow's state transitions and actions. Each transition in the matrix represents
 * a state change with associated actions to execute.</p>
 *
 * <p>This class is designed to be populated from YAML or JSON workflow definitions
 * using deserialization frameworks like SnakeYAML or Jackson.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class MatrixCode {

    String name;
    List<Transition> steps;

    /**
     * Constructs an empty MatrixCode.
     *
     * <p>This no-argument constructor is required for deserialization.</p>
     */
    public MatrixCode() {}

    /**
     * Returns the name of this workflow.
     *
     * @return the workflow name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of this workflow.
     *
     * @param name the workflow name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the workflow transitions.
     *
     * @return a list of {@link Transition} objects representing the workflow transitions
     * @since 2.12.0
     */
    public List<Transition> getTransitions() {
        return steps;
    }

    /**
     * Sets the workflow transitions.
     *
     * @param transitions a list of {@link Transition} objects representing the workflow transitions
     * @since 2.12.0
     */
    public void setTransitions(List<Transition> transitions) {
        this.steps = transitions;
    }

    /**
     * Returns the workflow transitions (alias for YAML 'steps' key).
     *
     * @return a list of {@link Transition} objects
     * @since 2.12.0
     */
    public List<Transition> getSteps() {
        return steps;
    }

    /**
     * Sets the workflow transitions (alias for YAML 'steps' key).
     *
     * @param steps a list of {@link Transition} objects
     * @since 2.12.0
     */
    public void setSteps(List<Transition> steps) {
        this.steps = steps;
    }

    /**
     * Returns the workflow transitions.
     *
     * <p>Since {@link Vertex} now extends {@link Transition}, the returned list
     * can be safely used where {@code List<Vertex>} is expected.</p>
     *
     * @return a list of transitions (as Vertex type for backward compatibility)
     * @deprecated Use {@link #getTransitions()} instead. This method will be removed in a future version.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public List<Vertex> getVertices() {
        // Safe because Vertex extends Transition, so List<Transition> can be viewed as List<Vertex>
        // for reading purposes (covariant return would be cleaner but changes signature)
        return (List<Vertex>) (List<?>) steps;
    }

    /**
     * Sets the workflow transitions.
     *
     * @param vertices a list of {@link Vertex} objects representing the workflow transitions
     * @deprecated Use {@link #setTransitions(List)} instead. This method will be removed in a future version.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public void setVertices(List<Vertex> vertices) {
        // Safe because Vertex extends Transition
        this.steps = (List<Transition>) (List<?>) vertices;
    }
}
