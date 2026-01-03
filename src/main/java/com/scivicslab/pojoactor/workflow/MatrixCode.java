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
 * <p>A MatrixCode contains a name and a list of {@link Vertex} objects that define
 * the workflow's state transitions and actions. Each vertex in the matrix represents
 * a state transition with associated actions to execute.</p>
 *
 * <p>This class is designed to be populated from YAML or JSON workflow definitions
 * using deserialization frameworks like SnakeYAML or Jackson.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class MatrixCode {

    String   name;
    List<Vertex> vertices;

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
     * Returns the workflow vertices.
     *
     * @return a list of {@link Vertex} objects representing the workflow vertices
     */
    public List<Vertex> getVertices() {
        return vertices;
    }

    /**
     * Sets the workflow vertices.
     *
     * @param vertices a list of {@link Vertex} objects representing the workflow vertices
     */
    public void setVertices(List<Vertex> vertices) {
        this.vertices = vertices;
    }

    /**
     * Returns the workflow vertices.
     *
     * @return a list of {@link Vertex} objects representing the workflow vertices
     * @deprecated Use {@link #getVertices()} instead
     */
    @Deprecated
    public List<Vertex> getSteps() {
        return vertices;
    }

    /**
     * Sets the workflow vertices.
     *
     * @param vertices a list of {@link Vertex} objects representing the workflow vertices
     * @deprecated Use {@link #setVertices(List)} instead
     */
    @Deprecated
    public void setSteps(List<Vertex> vertices) {
        this.vertices = vertices;
    }



}
