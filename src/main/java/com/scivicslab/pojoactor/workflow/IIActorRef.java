/*
 * Copyright 2025 devteam@scivicslab.com
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

import org.json.JSONObject;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.core.ActionResult;

/**
 * An interpreter-interfaced actor reference that can be invoked by action name strings.
 *
 * <p>This abstract class extends {@link ActorRef} and implements {@link CallableByActionName},
 * providing a bridge between the POJO-actor framework and the workflow interpreter.
 * It allows actors to be invoked dynamically using string-based action names, which is
 * essential for data-driven workflow execution.</p>
 *
 * @param <T> the type of the actor object being referenced
 * @author devteam@scivicslab.com
 */
public abstract class IIActorRef<T> extends ActorRef<T> implements CallableByActionName {


    /**
     * Constructs a new IIActorRef with the specified actor name and object.
     *
     * @param actorName the name of the actor
     * @param object the actor object instance
     */
    public IIActorRef(String actorName, T object) {
        super(actorName, object);
    }

    /**
     * Constructs a new IIActorRef with the specified actor name, object, and actor system.
     *
     * @param actorName the name of the actor
     * @param object the actor object instance
     * @param system the actor system managing this actor
     */
    public IIActorRef(String actorName, T object, IIActorSystem system) {
        super(actorName, object, system);
    }


    /**
     * Invokes an action by name on this actor.
     *
     * <p>This default implementation handles the JSON State API actions:</p>
     * <ul>
     *   <li>{@code putJson} - Store a value at a path</li>
     *   <li>{@code getJson} - Get a value from a path</li>
     *   <li>{@code hasJson} - Check if a path exists</li>
     *   <li>{@code clearJson} - Clear all JSON state</li>
     *   <li>{@code printJson} - Print JSON state for debugging</li>
     * </ul>
     *
     * <p>Subclasses should override this method and call {@code super.callByActionName()}
     * for unhandled actions to get JSON State API support.</p>
     *
     * @param actionName the name of the action to invoke
     * @param args the arguments as a JSON string
     * @return the result of the action
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        return switch (actionName) {
            case "putJson" -> handlePutJson(args);
            case "getJson" -> handleGetJson(args);
            case "hasJson" -> handleHasJson(args);
            case "clearJson" -> handleClearJson();
            case "printJson" -> handlePrintJson();
            default -> new ActionResult(false, "Unknown action: " + actionName);
        };
    }

    /**
     * Handles putJson action.
     * Expected args: {"path": "key.path", "value": <any>}
     */
    private ActionResult handlePutJson(String args) {
        try {
            JSONObject json = new JSONObject(args);
            String path = json.getString("path");
            Object value = json.get("value");
            putJson(path, value);
            return new ActionResult(true, "Stored " + path + "=" + value);
        } catch (Exception e) {
            return new ActionResult(false, "putJson error: " + e.getMessage());
        }
    }

    /**
     * Handles getJson action.
     * Expected args: ["path"] or "path"
     */
    private ActionResult handleGetJson(String args) {
        try {
            String path = parseFirstArgument(args);
            String value = getJsonString(path);
            return new ActionResult(true, value != null ? value : "");
        } catch (Exception e) {
            return new ActionResult(false, "getJson error: " + e.getMessage());
        }
    }

    /**
     * Handles hasJson action.
     * Expected args: ["path"] or "path"
     */
    private ActionResult handleHasJson(String args) {
        try {
            String path = parseFirstArgument(args);
            boolean exists = hasJson(path);
            return new ActionResult(true, exists ? "true" : "false");
        } catch (Exception e) {
            return new ActionResult(false, "hasJson error: " + e.getMessage());
        }
    }

    /**
     * Handles clearJson action.
     */
    private ActionResult handleClearJson() {
        clearJsonState();
        return new ActionResult(true, "JSON state cleared");
    }

    /**
     * Handles printJson action.
     */
    private ActionResult handlePrintJson() {
        System.out.println(json().toPrettyString());
        return new ActionResult(true, "Printed JSON state");
    }

    /**
     * Parses the first argument from a JSON array or returns the string as-is.
     */
    protected String parseFirstArgument(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "";
        }
        if (arg.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(arg);
                if (arr.length() > 0) {
                    return arr.getString(0);
                }
            } catch (Exception e) {
                // Not a valid JSON array
            }
        }
        return arg;
    }

}
