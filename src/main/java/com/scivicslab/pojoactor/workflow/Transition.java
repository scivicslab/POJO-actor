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
 * Represents a single transition in the workflow graph.
 *
 * <p>Each transition defines a state change with associated actions. The states
 * list typically contains two elements: the current state and the next state.
 * The actions can be represented in two ways:</p>
 * <ul>
 *   <li>Legacy format: List of string lists [actorName, actionName, argument]</li>
 *   <li>New format: List of Action objects with execution mode support</li>
 * </ul>
 *
 * <p>This class is designed to be populated from YAML workflow definitions
 * using deserialization frameworks like SnakeYAML or Jackson.</p>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.0
 */
public class Transition {

    List<String> states;
    String transitionName;  // Optional identifier for overlay matching
    List<Action> actions;  // Unified format for all workflow types

    /**
     * Returns the list of Action objects for this transition.
     *
     * @return a list of Action objects
     */
    public List<Action> getActions() {
        return this.actions;
    }

    /**
     * Returns the list of states for this transition.
     *
     * <p>Typically contains two elements: [currentState, nextState]</p>
     *
     * @return a list of state identifiers
     */
    public List<String> getStates() {
        return this.states;
    }

    /**
     * Sets the list of actions for this transition.
     *
     * @param actions a list of Action objects
     */
    public void setActions(List<Action> actions) {
        this.actions = actions;
    }

    /**
     * Sets the list of states for this transition.
     *
     * @param list a list of state identifiers
     */
    public void setStates(List<String> list) {
        this.states = list;
    }

    /**
     * Returns the transition name.
     *
     * <p>The transition name is used as a stable identifier for overlay matching.
     * When applying patches, transitions are matched by this name rather than
     * by states or array index.</p>
     *
     * @return the transition name, or null if not set
     */
    public String getTransitionName() {
        return this.transitionName;
    }

    /**
     * Sets the transition name.
     *
     * @param transitionName the transition name identifier
     */
    public void setTransitionName(String transitionName) {
        this.transitionName = transitionName;
    }

    /**
     * Returns a YAML-formatted string representation of this transition.
     *
     * <p>This method reconstructs a YAML-like format from the parsed data,
     * useful for debugging and visualization. The output shows the first
     * N lines of the transition definition.</p>
     *
     * <p>Example output:</p>
     * <pre>
     * - states: ["0", "1"]
     *   actions:
     *     - actor: node
     *       method: executeCommand
     *       arguments: ["ls -la"]
     * </pre>
     *
     * @param maxLines maximum number of lines to include (0 for unlimited)
     * @return YAML-formatted string representation
     */
    public String toYamlString(int maxLines) {
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;

        // states line
        sb.append("- states: [\"").append(states.get(0)).append("\", \"").append(states.get(1)).append("\"]\n");
        lineCount++;
        if (maxLines > 0 && lineCount >= maxLines) {
            return sb.toString();
        }

        // transitionName (if present)
        if (transitionName != null && !transitionName.isEmpty()) {
            sb.append("  transitionName: ").append(transitionName).append("\n");
            lineCount++;
            if (maxLines > 0 && lineCount >= maxLines) {
                return sb.toString();
            }
        }

        // actions header
        if (actions != null && !actions.isEmpty()) {
            sb.append("  actions:\n");
            lineCount++;
            if (maxLines > 0 && lineCount >= maxLines) {
                return sb.toString();
            }

            for (Action action : actions) {
                // actor line
                sb.append("    - actor: ").append(action.getActor()).append("\n");
                lineCount++;
                if (maxLines > 0 && lineCount >= maxLines) {
                    return sb.toString();
                }

                // method line
                sb.append("      method: ").append(action.getMethod()).append("\n");
                lineCount++;
                if (maxLines > 0 && lineCount >= maxLines) {
                    return sb.toString();
                }

                // arguments line (if present)
                Object args = action.getArguments();
                if (args != null) {
                    String argsStr = formatArguments(args);
                    sb.append("      arguments: ").append(argsStr).append("\n");
                    lineCount++;
                    if (maxLines > 0 && lineCount >= maxLines) {
                        return sb.toString();
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Formats arguments for YAML output.
     */
    private String formatArguments(Object args) {
        if (args instanceof String) {
            String s = (String) args;
            // Truncate long strings
            if (s.length() > 120) {
                s = s.substring(0, 117) + "...";
            }
            return "\"" + s + "\"";
        } else if (args instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) args;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                Object item = list.get(i);
                if (item instanceof String) {
                    String s = (String) item;
                    if (s.length() > 120) {
                        s = s.substring(0, 117) + "...";
                    }
                    sb.append("\"").append(s).append("\"");
                } else {
                    sb.append(item);
                }
            }
            sb.append("]");
            return sb.toString();
        } else if (args instanceof java.util.Map) {
            // For maps, just show a summary
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) args;
            return "{...} (" + map.size() + " keys)";
        } else {
            String s = args.toString();
            if (s.length() > 120) {
                s = s.substring(0, 117) + "...";
            }
            return s;
        }
    }
}
