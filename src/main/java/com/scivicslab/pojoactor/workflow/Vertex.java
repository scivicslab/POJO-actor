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
 * Represents a single vertex in the workflow graph.
 *
 * @author devteam@scivics-lab.com
 * @deprecated Use {@link Transition} instead. This class will be removed in a future version.
 */
@Deprecated
public class Vertex extends Transition {

    /**
     * Returns the vertex name.
     *
     * @return the vertex name, or null if not set
     * @deprecated Use {@link #getLabel()} instead.
     */
    @Deprecated
    public String getVertexName() {
        return getLabel();
    }

    /**
     * Sets the vertex name.
     *
     * @param vertexName the vertex name identifier
     * @deprecated Use {@link #setLabel(String)} instead.
     */
    @Deprecated
    public void setVertexName(String vertexName) {
        setLabel(vertexName);
    }
}
