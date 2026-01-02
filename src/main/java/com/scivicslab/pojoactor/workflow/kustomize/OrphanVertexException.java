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

package com.scivicslab.pojoactor.workflow.kustomize;

/**
 * Exception thrown when a patch contains a new vertex without an anchor.
 *
 * <p>When adding new vertices via overlay, the patch must include at least
 * one vertex that matches an existing vertex in the base workflow (an "anchor").
 * New vertices are inserted relative to the anchor's position.</p>
 *
 * <p>If a patch contains only vertices that don't exist in the base,
 * this exception is thrown because the insertion position cannot be determined.</p>
 *
 * @author devteam@scivics-lab.com
 * @since 2.9.0
 */
public class OrphanVertexException extends RuntimeException {

    private final String vertexName;
    private final String patchFile;

    /**
     * Constructs a new OrphanVertexException.
     *
     * @param vertexName the name of the orphan vertex
     * @param patchFile the patch file containing the orphan vertex
     */
    public OrphanVertexException(String vertexName, String patchFile) {
        super(String.format(
            "Orphan vertex '%s' in patch '%s'. " +
            "New vertices must be accompanied by at least one vertex that exists in the base workflow.",
            vertexName, patchFile));
        this.vertexName = vertexName;
        this.patchFile = patchFile;
    }

    /**
     * Gets the name of the orphan vertex.
     *
     * @return the vertex name
     */
    public String getVertexName() {
        return vertexName;
    }

    /**
     * Gets the patch file name.
     *
     * @return the patch file name
     */
    public String getPatchFile() {
        return patchFile;
    }
}
