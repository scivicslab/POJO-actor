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

import com.scivicslab.pojoactor.core.ActionResult;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Root actor for the IIActorSystem hierarchy.
 *
 * <p>This actor serves as the top-level parent for all user-created actors
 * in the system. Having a ROOT actor provides several benefits:</p>
 * <ul>
 *   <li>Easy enumeration of top-level actors via {@code getChildren}</li>
 *   <li>Unified actor tree traversal starting from a single root</li>
 *   <li>Clear separation between system infrastructure and user actors</li>
 * </ul>
 *
 * <p>The ROOT actor is automatically created when an {@link IIActorSystem}
 * is instantiated. User actors added via {@link IIActorSystem#addIIActor}
 * become children of ROOT.</p>
 *
 * <h2>Actor Tree Structure</h2>
 * <pre>
 * IIActorSystem
 * └── ROOT
 *     ├── loader
 *     │   └── analyzer
 *     └── nodeGroup
 *         ├── node-192.168.5.13
 *         └── node-192.168.5.14
 * </pre>
 *
 * <h2>Available Actions</h2>
 * <ul>
 *   <li>{@code listChildren} - Returns names of all top-level actors</li>
 *   <li>{@code getChildCount} - Returns the number of top-level actors</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.0
 */
public class RootIIAR extends IIActorRef<Object> {

    /** The name of the root actor. */
    public static final String ROOT_NAME = "ROOT";

    /**
     * Constructs a new RootIIAR.
     *
     * @param system the actor system managing this root actor
     */
    public RootIIAR(IIActorSystem system) {
        super(ROOT_NAME, new Object(), system);
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        return switch (actionName) {
            case "listChildren" -> listChildren();
            case "getChildCount" -> getChildCount();
            default -> new ActionResult(false, "Unknown action: " + actionName);
        };
    }

    /**
     * Lists all top-level actor names.
     *
     * @return ActionResult containing comma-separated list of child actor names
     */
    private ActionResult listChildren() {
        Set<String> children = getNamesOfChildren();
        if (children.isEmpty()) {
            return new ActionResult(true, "No children");
        }
        String result = children.stream()
            .sorted()
            .collect(Collectors.joining(", "));
        return new ActionResult(true, result);
    }

    /**
     * Returns the number of top-level actors.
     *
     * @return ActionResult containing the child count
     */
    private ActionResult getChildCount() {
        return new ActionResult(true, String.valueOf(getNamesOfChildren().size()));
    }
}
