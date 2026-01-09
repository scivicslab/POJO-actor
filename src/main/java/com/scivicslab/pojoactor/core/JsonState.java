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

package com.scivicslab.pojoactor.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Optional;

/**
 * JSON state container with XPath-style path accessor.
 *
 * <p>This class provides a lightweight way to store and retrieve dynamic state
 * using XPath-like path expressions. It is designed for workflow state management
 * where compile-time type safety is not required.</p>
 *
 * <h2>Path Syntax</h2>
 * <ul>
 *   <li>{@code $.key} or {@code key} - access object property</li>
 *   <li>{@code $.parent.child} - nested property access</li>
 *   <li>{@code $.array[0]} - array index access</li>
 *   <li>{@code $.data[0].name} - combined access</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * JsonState state = new JsonState();
 *
 * // Write values
 * state.put("workflow.retry", 3);
 * state.put("hosts[0]", "server1.example.com");
 *
 * // Read values
 * int retry = state.getInt("$.workflow.retry", 0);
 * String host = state.getString("$.hosts[0]");
 *
 * // Check existence
 * if (state.has("$.workflow.timeout")) {
 *     // ...
 * }
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.10.0
 */
public class JsonState {

    private static final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode root;

    /**
     * Constructs an empty JsonState.
     */
    public JsonState() {
        this.root = mapper.createObjectNode();
    }

    /**
     * Constructs a JsonState from an existing ObjectNode.
     *
     * @param root the root node
     */
    public JsonState(ObjectNode root) {
        this.root = root != null ? root : mapper.createObjectNode();
    }

    // ========================================================================
    // Path Resolution (Read)
    // ========================================================================

    /**
     * Selects a JsonNode at the given path.
     *
     * <p>Path syntax:</p>
     * <ul>
     *   <li>{@code $.key} or {@code key} - object property</li>
     *   <li>{@code $.parent.child} - nested property</li>
     *   <li>{@code $.array[0]} - array index</li>
     * </ul>
     *
     * @param path the XPath-style path expression
     * @return the JsonNode at the path, or MissingNode if not found
     */
    public JsonNode select(String path) {
        if (path == null || path.isEmpty()) {
            return root;
        }

        // Remove leading "$." if present
        String normalizedPath = path.startsWith("$.") ? path.substring(2) : path;
        if (normalizedPath.startsWith(".")) {
            normalizedPath = normalizedPath.substring(1);
        }

        JsonNode current = root;

        for (String part : splitPath(normalizedPath)) {
            if (part.isEmpty()) {
                continue;
            }

            // Check for array index: name[index]
            if (part.contains("[")) {
                int bracketStart = part.indexOf('[');
                int bracketEnd = part.indexOf(']');

                if (bracketStart > 0) {
                    // Has name before bracket: field[0]
                    String fieldName = part.substring(0, bracketStart);
                    current = current.path(fieldName);
                }

                // Extract index
                String indexStr = part.substring(bracketStart + 1, bracketEnd);
                int index = Integer.parseInt(indexStr);
                current = current.path(index);
            } else {
                // Simple field access
                current = current.path(part);
            }

            if (current.isMissingNode()) {
                return current;
            }
        }

        return current;
    }

    /**
     * Splits path by dots, but preserves array brackets.
     */
    private String[] splitPath(String path) {
        // Simple split - handles most cases
        // For complex cases with dots inside brackets, would need more sophisticated parsing
        return path.split("\\.");
    }

    // ========================================================================
    // Typed Getters
    // ========================================================================

    /**
     * Gets a string value at the path.
     *
     * @param path the path expression
     * @return the string value, or null if not found
     */
    public String getString(String path) {
        JsonNode node = select(path);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /**
     * Gets a string value at the path with default.
     *
     * @param path the path expression
     * @param defaultValue the default value if not found
     * @return the string value, or defaultValue if not found
     */
    public String getString(String path, String defaultValue) {
        JsonNode node = select(path);
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asText();
    }

    /**
     * Gets an integer value at the path.
     *
     * @param path the path expression
     * @param defaultValue the default value if not found
     * @return the integer value, or defaultValue if not found
     */
    public int getInt(String path, int defaultValue) {
        JsonNode node = select(path);
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asInt(defaultValue);
    }

    /**
     * Gets a long value at the path.
     *
     * @param path the path expression
     * @param defaultValue the default value if not found
     * @return the long value, or defaultValue if not found
     */
    public long getLong(String path, long defaultValue) {
        JsonNode node = select(path);
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asLong(defaultValue);
    }

    /**
     * Gets a double value at the path.
     *
     * @param path the path expression
     * @param defaultValue the default value if not found
     * @return the double value, or defaultValue if not found
     */
    public double getDouble(String path, double defaultValue) {
        JsonNode node = select(path);
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asDouble(defaultValue);
    }

    /**
     * Gets a boolean value at the path.
     *
     * @param path the path expression
     * @param defaultValue the default value if not found
     * @return the boolean value, or defaultValue if not found
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        JsonNode node = select(path);
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asBoolean(defaultValue);
    }

    /**
     * Gets an Optional value at the path.
     *
     * @param path the path expression
     * @return Optional containing the JsonNode, or empty if not found
     */
    public Optional<JsonNode> get(String path) {
        JsonNode node = select(path);
        return node.isMissingNode() || node.isNull() ? Optional.empty() : Optional.of(node);
    }

    /**
     * Checks if a value exists at the path.
     *
     * @param path the path expression
     * @return true if a non-null value exists at the path
     */
    public boolean has(String path) {
        JsonNode node = select(path);
        return !node.isMissingNode() && !node.isNull();
    }

    // ========================================================================
    // Path Resolution (Write)
    // ========================================================================

    /**
     * Sets a value at the given path, creating intermediate nodes as needed.
     *
     * <p>Path syntax is the same as {@link #select(String)}.</p>
     *
     * @param path the path expression
     * @param value the value to set (String, Number, Boolean, or null)
     * @return this JsonState for method chaining
     */
    public JsonState put(String path, Object value) {
        if (path == null || path.isEmpty()) {
            return this;
        }

        // Remove leading "$." if present
        String normalizedPath = path.startsWith("$.") ? path.substring(2) : path;
        if (normalizedPath.startsWith(".")) {
            normalizedPath = normalizedPath.substring(1);
        }

        String[] parts = splitPath(normalizedPath);
        ObjectNode current = root;

        // Navigate to parent, creating nodes as needed
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            if (part.contains("[")) {
                current = navigateOrCreateArray(current, part);
            } else {
                // Create object node if doesn't exist
                JsonNode child = current.get(part);
                if (child == null || !child.isObject()) {
                    current.set(part, mapper.createObjectNode());
                }
                current = (ObjectNode) current.get(part);
            }
        }

        // Set the final value
        String lastPart = parts[parts.length - 1];
        if (lastPart.contains("[")) {
            setArrayValue(current, lastPart, value);
        } else {
            setNodeValue(current, lastPart, value);
        }

        return this;
    }

    /**
     * Navigates or creates array structure.
     */
    private ObjectNode navigateOrCreateArray(ObjectNode parent, String part) {
        int bracketStart = part.indexOf('[');
        int bracketEnd = part.indexOf(']');
        String fieldName = part.substring(0, bracketStart);
        int index = Integer.parseInt(part.substring(bracketStart + 1, bracketEnd));

        // Ensure array exists
        JsonNode arrayNode = parent.get(fieldName);
        ArrayNode array;
        if (arrayNode == null || !arrayNode.isArray()) {
            array = mapper.createArrayNode();
            parent.set(fieldName, array);
        } else {
            array = (ArrayNode) arrayNode;
        }

        // Ensure element exists at index
        while (array.size() <= index) {
            array.add(mapper.createObjectNode());
        }

        JsonNode element = array.get(index);
        if (element.isObject()) {
            return (ObjectNode) element;
        } else {
            ObjectNode newObj = mapper.createObjectNode();
            array.set(index, newObj);
            return newObj;
        }
    }

    /**
     * Sets value in an array element.
     */
    private void setArrayValue(ObjectNode parent, String part, Object value) {
        int bracketStart = part.indexOf('[');
        int bracketEnd = part.indexOf(']');
        String fieldName = bracketStart > 0 ? part.substring(0, bracketStart) : null;
        int index = Integer.parseInt(part.substring(bracketStart + 1, bracketEnd));

        ArrayNode array;
        if (fieldName != null) {
            JsonNode arrayNode = parent.get(fieldName);
            if (arrayNode == null || !arrayNode.isArray()) {
                array = mapper.createArrayNode();
                parent.set(fieldName, array);
            } else {
                array = (ArrayNode) arrayNode;
            }
        } else {
            // This shouldn't happen in normal usage
            return;
        }

        // Ensure array is large enough
        while (array.size() <= index) {
            array.addNull();
        }

        // Set the value
        if (value == null) {
            array.setNull(index);
        } else if (value instanceof String) {
            array.set(index, mapper.valueToTree(value));
        } else if (value instanceof Number) {
            array.set(index, mapper.valueToTree(value));
        } else if (value instanceof Boolean) {
            array.set(index, mapper.valueToTree(value));
        } else if (value instanceof JsonNode) {
            array.set(index, (JsonNode) value);
        } else {
            array.set(index, mapper.valueToTree(value.toString()));
        }
    }

    /**
     * Sets a value on an ObjectNode.
     */
    private void setNodeValue(ObjectNode parent, String fieldName, Object value) {
        if (value == null) {
            parent.putNull(fieldName);
        } else if (value instanceof String) {
            parent.put(fieldName, (String) value);
        } else if (value instanceof Integer) {
            parent.put(fieldName, (Integer) value);
        } else if (value instanceof Long) {
            parent.put(fieldName, (Long) value);
        } else if (value instanceof Double) {
            parent.put(fieldName, (Double) value);
        } else if (value instanceof Float) {
            parent.put(fieldName, (Float) value);
        } else if (value instanceof Boolean) {
            parent.put(fieldName, (Boolean) value);
        } else if (value instanceof JsonNode) {
            parent.set(fieldName, (JsonNode) value);
        } else {
            parent.put(fieldName, value.toString());
        }
    }

    /**
     * Removes a value at the given path.
     *
     * @param path the path expression
     * @return true if a value was removed
     */
    public boolean remove(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }

        // Remove leading "$." if present
        String normalizedPath = path.startsWith("$.") ? path.substring(2) : path;
        if (normalizedPath.startsWith(".")) {
            normalizedPath = normalizedPath.substring(1);
        }

        String[] parts = splitPath(normalizedPath);
        JsonNode current = root;

        // Navigate to parent
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            if (part.contains("[")) {
                int bracketStart = part.indexOf('[');
                int bracketEnd = part.indexOf(']');
                String fieldName = part.substring(0, bracketStart);
                int index = Integer.parseInt(part.substring(bracketStart + 1, bracketEnd));
                current = current.path(fieldName).path(index);
            } else {
                current = current.path(part);
            }

            if (current.isMissingNode()) {
                return false;
            }
        }

        // Remove the final element
        if (current instanceof ObjectNode) {
            String lastPart = parts[parts.length - 1];
            if (lastPart.contains("[")) {
                // Array element removal is complex, skip for now
                return false;
            }
            return ((ObjectNode) current).remove(lastPart) != null;
        }

        return false;
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Returns the root ObjectNode for direct manipulation.
     *
     * @return the root node
     */
    public ObjectNode getRoot() {
        return root;
    }

    /**
     * Checks if the state is empty.
     *
     * @return true if no values are stored
     */
    public boolean isEmpty() {
        return root.isEmpty();
    }

    /**
     * Returns the number of top-level keys.
     *
     * @return the number of keys
     */
    public int size() {
        return root.size();
    }

    /**
     * Clears all values.
     */
    public void clear() {
        root.removeAll();
    }

    /**
     * Returns the JSON string representation.
     *
     * @return JSON string
     */
    @Override
    public String toString() {
        return root.toString();
    }

    /**
     * Returns a pretty-printed JSON string.
     *
     * @return formatted JSON string
     */
    public String toPrettyString() {
        return root.toPrettyString();
    }

    /**
     * Returns an iterator over the top-level field names.
     *
     * @return field name iterator
     */
    public Iterator<String> fieldNames() {
        return root.fieldNames();
    }

    /**
     * Creates a deep copy of this JsonState.
     *
     * @return a new JsonState with copied data
     */
    public JsonState copy() {
        return new JsonState(root.deepCopy());
    }
}
