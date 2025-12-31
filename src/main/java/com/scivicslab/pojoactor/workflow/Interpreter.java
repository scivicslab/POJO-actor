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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.pojoactor.core.ActionResult;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Workflow interpreter that executes matrix-based workflow definitions.
 *
 * <p>The Interpreter reads workflow definitions in YAML or JSON format and
 * executes them by invoking actions on registered actors. Workflows are
 * represented as a matrix of states and actions, where each row defines
 * state transitions and the actions to execute during those transitions.</p>
 *
 * <p>This class maintains the current execution state, including the current
 * row and state, and coordinates with an {@link IIActorSystem} to invoke
 * actor methods dynamically.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class Interpreter {

    protected Logger logger = null;

    protected MatrixCode code = null;

    protected int currentRow = 0;

    protected String currentState = "0";

    protected IIActorSystem system = null;

    /**
     * Reference to the actor executing this interpreter (for Unix-style path resolution).
     * When this interpreter is running inside an actor (e.g., Node extends Interpreter),
     * this field holds a reference to that actor, enabling relative path resolution
     * like "." (self), ".." (parent), "../sibling" (sibling actors), etc.
     */
    protected IIActorRef<?> selfActorRef = null;

    /**
     * Builder for constructing Interpreter instances.
     *
     * <p>Provides a fluent API for configuring the interpreter with
     * a logger name and actor system.</p>
     */
     public static class Builder {

         IIActorSystem system = null;

         String loggerName = null;

         /**
          * Constructs a new Builder.
          */
         public Builder() {}

         /**
          * Sets the logger name for this interpreter.
          *
          * @param name the logger name
          * @return this builder for method chaining
          */
         public Builder loggerName(String name) {
             loggerName = name;
             return this;
         }

         /**
          * Sets the actor system for this interpreter.
          *
          * @param system the actor system managing the workflow actors
          * @return this builder for method chaining
          */
         public Builder team(IIActorSystem system) {
             this.system = system;
             return this;
         }

         /**
          * Builds and returns a new Interpreter instance.
          *
          * @return a new configured Interpreter
          */
         public Interpreter build() {
             Interpreter obj = new Interpreter();
             obj.logger = Logger.getLogger(loggerName);
             obj.system = this.system;
             return obj;
         }
     }

    /**
     * Executes the actions in the current row of the workflow matrix.
     *
     * <p>For each action in the current row, this method retrieves the
     * corresponding actor(s) from the system and invokes the specified action
     * by name with the provided arguments.</p>
     *
     * <p>Supports Unix-style actor path resolution when {@code selfActorRef} is set:</p>
     * <ul>
     *   <li>{@code .} or {@code this} - self (the current interpreter's actor)</li>
     *   <li>{@code ..} - parent actor</li>
     *   <li>{@code ./*} - all children</li>
     *   <li>{@code ../*} - all siblings</li>
     *   <li>{@code ../sibling} - specific sibling by name</li>
     *   <li>{@code ../web*} - siblings matching wildcard pattern</li>
     * </ul>
     *
     * <p>If {@code selfActorRef} is not set, falls back to absolute actor name lookup.</p>
     *
     * <p>Supports both legacy {@code argument} (String) and new {@code arguments} (List/Map) formats.
     * If {@code arguments} is present, it is converted to JSON array format before passing to the actor.</p>
     *
     * @return an {@link ActionResult} indicating success or failure
     */
    public ActionResult action() {
        Row row = code.getSteps().get(currentRow);
        for (Action a: row.getActions()) {
            String actorPath = a.getActor();
            String action    = a.getMethod();

            // Convert arguments to JSON format
            String argumentString = convertArgumentsToJson(a);

            // Resolve actor path using Unix-style notation
            List<IIActorRef<?>> actors;
            if (selfActorRef != null) {
                // Use path resolution relative to self
                actors = system.resolveActorPath(selfActorRef.getName(), actorPath);
            } else {
                // Fallback: treat as absolute actor name
                IIActorRef<?> actor = system.getIIActor(actorPath);
                actors = actor != null ? Arrays.asList(actor) : new ArrayList<>();
            }

            // Execute action on all matching actors
            for (IIActorRef<?> actorAR : actors) {
                ActionResult result;

                // Execute based on ExecutionMode
                ExecutionMode executionMode = a.getExecution();
                if (executionMode == null) {
                    executionMode = ExecutionMode.POOL;  // Default to POOL
                }

                if (executionMode == ExecutionMode.POOL && system != null) {
                    // POOL: Execute on WorkStealingPool (safe for heavy operations)
                    try {
                        result = CompletableFuture.supplyAsync(
                            () -> actorAR.callByActionName(action, argumentString),
                            system.getWorkStealingPool()
                        ).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.log(Level.WARNING, "Action interrupted", e);
                        return new ActionResult(false, "Action interrupted: " + e.getMessage());
                    } catch (ExecutionException e) {
                        logger.log(Level.WARNING, "Action execution failed", e);
                        return new ActionResult(false, "Action failed: " + e.getCause().getMessage());
                    }
                } else {
                    // DIRECT: Direct synchronous call (for light operations)
                    result = actorAR.callByActionName(action, argumentString);
                }

                // If any action returns false, abort this step
                if (!result.isSuccess()) {
                    return result;
                }
            }
        }
        return new ActionResult(true, "");
    }

    /**
     * Converts action arguments to JSON array format.
     *
     * <p>Handles multiple {@code arguments} formats:</p>
     * <ul>
     *   <li>If {@code arguments} is a String: wraps in JSON array (e.g., {@code "value"} → {@code ["value"]})</li>
     *   <li>If {@code arguments} is a List: converts to JSON array string (e.g., {@code ["a","b"]})</li>
     *   <li>If {@code arguments} is a Map: wraps in JSON array (e.g., {@code [{"key":"value"}]})</li>
     *   <li>If {@code arguments} is null or empty List: returns empty array {@code []}</li>
     * </ul>
     *
     * @param action the action containing arguments
     * @return JSON array string (empty array "[]" if no arguments)
     */
    private String convertArgumentsToJson(Action action) {
        Object arguments = action.getArguments();

        // null or empty list → return empty JSON array "[]"
        if (arguments == null) {
            return "[]";
        }

        if (arguments instanceof String) {
            // Single string argument: wrap in JSON array
            JSONArray jsonArray = new JSONArray();
            jsonArray.put((String) arguments);
            return jsonArray.toString();
        } else if (arguments instanceof List) {
            // Convert List to JSON array: ["arg1", "arg2"] or []
            JSONArray jsonArray = new JSONArray((List<?>) arguments);
            return jsonArray.toString();
        } else if (arguments instanceof Map) {
            // Convert Map to JSON array with single object: [{"key": "value"}]
            JSONObject jsonObject = new JSONObject((Map<?, ?>) arguments);
            JSONArray jsonArray = new JSONArray();
            jsonArray.put(jsonObject);
            return jsonArray.toString();
        } else {
            throw new IllegalArgumentException(
                "Unsupported arguments type: " + arguments.getClass().getName() +
                ". Expected String, List, or Map.");
        }
    }

    /**
     * Returns the currently loaded workflow code.
     *
     * @return the {@link MatrixCode} representing the workflow
     */
    public MatrixCode getCode() {
        return this.code;
    }

    /**
     * Sets the workflow code directly (for testing purposes).
     *
     * @param code the workflow code to set
     */
    public void setCode(MatrixCode code) {
        this.code = code;
    }

    /**
     * Reads and parses a workflow definition from a YAML input stream.
     *
     * @param yamlInput the YAML input stream containing the workflow definition
     */
    public void readYaml(InputStream yamlInput) {

        Yaml yaml = new Yaml(new Constructor(MatrixCode.class, new LoaderOptions()));
        code = yaml.load(yamlInput);

    }

    /**
     * Reads and parses a workflow definition from a JSON input stream.
     *
     * @param jsonInput the JSON input stream containing the workflow definition
     * @throws IOException if an I/O error occurs during parsing
     */
    public void readJson(InputStream jsonInput) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        code = mapper.readValue(jsonInput, MatrixCode.class);
    }

    /**
     * Reads and parses a workflow definition from an XML input stream.
     *
     * <p>The XML format follows this structure:</p>
     * <pre>{@code
     * <workflow name="workflow-name">
     *   <steps>
     *     <transition from="state1" to="state2">
     *       <action actor="actorName" method="methodName">argument</action>
     *     </transition>
     *   </steps>
     * </workflow>
     * }</pre>
     *
     * @param xmlInput the XML input stream containing the workflow definition
     * @throws IOException if an I/O error occurs during parsing
     * @throws ParserConfigurationException if the XML parser cannot be configured
     * @throws SAXException if the XML is malformed
     */
    public void readXml(InputStream xmlInput) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlInput);
        doc.getDocumentElement().normalize();

        // Create MatrixCode instance
        code = new MatrixCode();

        // Get workflow name
        Element workflowElement = doc.getDocumentElement();
        String workflowName = workflowElement.getAttribute("name");
        code.setName(workflowName);

        // Parse steps
        List<Row> steps = new ArrayList<>();
        NodeList stepsNodes = workflowElement.getElementsByTagName("steps");

        if (stepsNodes.getLength() > 0) {
            Element stepsElement = (Element) stepsNodes.item(0);
            NodeList transitionNodes = stepsElement.getElementsByTagName("transition");

            for (int i = 0; i < transitionNodes.getLength(); i++) {
                Element transitionElement = (Element) transitionNodes.item(i);

                // Create Row instance
                Row row = new Row();

                // Parse states (from, to)
                List<String> states = new ArrayList<>();
                states.add(transitionElement.getAttribute("from"));
                states.add(transitionElement.getAttribute("to"));
                row.setStates(states);

                // Parse actions
                List<Action> actions = new ArrayList<>();
                NodeList actionNodes = transitionElement.getElementsByTagName("action");

                for (int j = 0; j < actionNodes.getLength(); j++) {
                    Element actionElement = (Element) actionNodes.item(j);

                    Action action = new Action();
                    action.setActor(actionElement.getAttribute("actor"));
                    action.setMethod(actionElement.getAttribute("method"));

                    // Check for new <arguments> element
                    NodeList argumentsNodes = actionElement.getElementsByTagName("arguments");
                    if (argumentsNodes.getLength() > 0) {
                        Element argumentsElement = (Element) argumentsNodes.item(0);
                        NodeList argNodes = argumentsElement.getElementsByTagName("arg");

                        if (argNodes.getLength() > 0) {
                            // Parse as list of arguments: <arguments><arg>a</arg><arg>b</arg></arguments>
                            List<String> argsList = new ArrayList<>();
                            for (int k = 0; k < argNodes.getLength(); k++) {
                                Element argElement = (Element) argNodes.item(k);
                                argsList.add(argElement.getTextContent().trim());
                            }
                            action.setArguments(argsList);
                        } else {
                            // Check if <arguments> has text content (single string format)
                            String textContent = argumentsElement.getTextContent().trim();
                            if (!textContent.isEmpty()) {
                                // Single string argument: <arguments>value</arguments>
                                action.setArguments(textContent);
                            } else {
                                // Empty <arguments/> element
                                action.setArguments(new ArrayList<>());
                            }
                        }
                    }
                    // No arguments element means no arguments

                    actions.add(action);
                }

                row.setActions(actions);
                steps.add(row);
            }
        }

        code.setSteps(steps);
    }

    /**
     * Executes the loaded workflow code.
     *
     * <p>This method implements finite automaton semantics:</p>
     * <ol>
     *   <li>Find a step whose from-state matches the current state</li>
     *   <li>Execute the actions in that step</li>
     *   <li>If any action returns false, skip to the next step and retry</li>
     *   <li>If all actions succeed, transition to the to-state</li>
     * </ol>
     *
     * <p>This enables conditional branching: multiple steps can have the same
     * from-state, and the first one whose actions all succeed will be taken.</p>
     *
     * @return an {@link ActionResult} indicating success or failure
     */
    public ActionResult execCode() {
        if (!hasCodeLoaded()) {
            return new ActionResult(false, "No code loaded");
        }

        // Try all rows, starting from currentRow and wrapping around
        int stepsCount = code.getSteps().size();
        for (int attempts = 0; attempts < stepsCount; attempts++) {
            // Wrap around to the beginning when reaching the end
            if (currentRow >= stepsCount) {
                currentRow = 0;
            }

            Row row = code.getSteps().get(currentRow);

            // Check if from-state matches current state
            if (matchesCurrentState(row)) {
                // Try to execute actions
                ActionResult actionResult = action();

                if (actionResult.isSuccess()) {
                    // All actions succeeded, transition to to-state
                    transitionTo(getToState(row));
                    return new ActionResult(true, "State: " + currentState);
                }
                // Action failed, try next step
            }
            currentRow++;
        }

        return new ActionResult(false, "No matching state transition");
    }

    /**
     * Executes the workflow until reaching the "end" state.
     *
     * <p>This method provides a self-contained way to run workflows that have
     * a defined termination state. The workflow author defines transitions to
     * the "end" state in the YAML file, and this method handles the execution
     * loop automatically.</p>
     *
     * <p>The method repeatedly calls {@link #execCode()} until:</p>
     * <ul>
     *   <li>The current state becomes "end" (success)</li>
     *   <li>An action returns failure (error)</li>
     *   <li>No matching state transition is found (error)</li>
     *   <li>Maximum iterations exceeded (error - prevents infinite loops)</li>
     * </ul>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * // YAML workflow with "end" state:
     * // steps:
     * //   - states: ["0", "1"]
     * //     actions:
     * //       - actor: worker
     * //         method: process
     * //   - states: ["1", "end"]
     * //     actions:
     * //       - actor: worker
     * //         method: finish
     *
     * Interpreter interpreter = new Interpreter.Builder()
     *     .loggerName("workflow")
     *     .team(system)
     *     .build();
     *
     * interpreter.readYaml(workflowStream);
     * ActionResult result = interpreter.runUntilEnd();
     *
     * if (result.isSuccess()) {
     *     System.out.println("Workflow completed successfully");
     * } else {
     *     System.out.println("Workflow failed: " + result.getResult());
     * }
     * }</pre>
     *
     * @return an {@link ActionResult} with success=true if "end" state reached,
     *         or success=false with error message if workflow failed
     * @see #runUntilEnd(int) for specifying custom maximum iterations
     * @since 2.8.0
     */
    public ActionResult runUntilEnd() {
        return runUntilEnd(10000);  // Default max iterations
    }

    /**
     * Executes the workflow until reaching the "end" state with a custom iteration limit.
     *
     * <p>This method runs the workflow with the following termination conditions:</p>
     * <ul>
     *   <li>Success: current state becomes "end" (automaton accepted)</li>
     *   <li>Failure: an action fails and no alternative row matches the current state</li>
     *   <li>Failure: maxIterations exceeded without reaching "end" (automaton did not accept)</li>
     * </ul>
     *
     * @param maxIterations maximum number of state transitions allowed
     * @return an {@link ActionResult} with success=true if "end" state reached,
     *         or success=false with error message if workflow failed or iterations exceeded
     * @see #runUntilEnd() for default iteration limit
     * @since 2.8.0
     */
    public ActionResult runUntilEnd(int maxIterations) {
        if (!hasCodeLoaded()) {
            return new ActionResult(false, "No code loaded");
        }

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            // Check for end state before executing
            if ("end".equals(currentState)) {
                return new ActionResult(true, "Workflow completed");
            }

            ActionResult result = execCode();

            if (!result.isSuccess()) {
                return new ActionResult(false, "Workflow failed at iteration " + iteration + ": " + result.getResult());
            }
        }

        return new ActionResult(false, "Maximum iterations (" + maxIterations + ") exceeded");
    }

    /**
     * Checks if workflow code is loaded.
     *
     * @return true if code is loaded and has at least one step
     */
    public boolean hasCodeLoaded() {
        return code != null && !code.getSteps().isEmpty();
    }

    /**
     * Checks if a row's from-state matches the current state.
     *
     * <p>A row matches if it has at least 2 states (from and to) and
     * the from-state (index 0) equals the interpreter's current state.</p>
     *
     * @param row the row to check
     * @return true if the row's from-state matches current state
     */
    public boolean matchesCurrentState(Row row) {
        List<String> states = row.getStates();
        return states.size() >= 2 && states.get(0).equals(currentState);
    }

    /**
     * Gets the to-state from a row.
     *
     * @param row the row containing the state transition
     * @return the to-state (second element), or null if not present
     */
    public String getToState(Row row) {
        List<String> states = row.getStates();
        return states.size() >= 2 ? states.get(1) : null;
    }

    /**
     * Transitions to a new state and finds the next matching row.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Updates the current state to the specified to-state</li>
     *   <li>Searches for the first row whose from-state matches the new current state</li>
     *   <li>Updates currentRow to point to that row</li>
     * </ol>
     *
     * @param toState the state to transition to
     */
    public void transitionTo(String toState) {
        currentState = toState;
        findNextMatchingRow();
    }

    /**
     * Finds the first row whose from-state matches the current state.
     *
     * <p>Searches from the beginning of the steps list and updates
     * currentRow to the index of the first matching row.</p>
     */
    protected void findNextMatchingRow() {
        int stepsCount = code.getSteps().size();
        for (int i = 0; i < stepsCount; i++) {
            Row nextRow = code.getSteps().get(i);
            if (!nextRow.getStates().isEmpty() && nextRow.getStates().get(0).equals(currentState)) {
                currentRow = i;
                return;
            }
        }
    }

    /**
     * Gets the current state of the interpreter.
     *
     * @return the current state string
     */
    public String getCurrentState() {
        return currentState;
    }

    /**
     * Gets the current row index.
     *
     * @return the current row index
     */
    public int getCurrentRow() {
        return currentRow;
    }

    /**
     * Resets the interpreter to its initial state.
     *
     * <p>This method resets the execution state of the interpreter, allowing it
     * to be reused for executing a new workflow without creating a new instance.
     * The following state is reset:</p>
     * <ul>
     *   <li>Current row index is reset to 0</li>
     *   <li>Current state is reset to "0" (initial state)</li>
     *   <li>Loaded workflow code is cleared</li>
     * </ul>
     *
     * <p>The following state is preserved:</p>
     * <ul>
     *   <li>Logger instance</li>
     *   <li>Actor system reference</li>
     * </ul>
     *
     * <p>This method is primarily used by {@link ReusableSubWorkflowCaller}
     * to reuse a single Interpreter instance across multiple sub-workflow calls,
     * reducing object allocation overhead.</p>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * Interpreter interpreter = new Interpreter.Builder()
     *     .loggerName("workflow")
     *     .team(system)
     *     .build();
     *
     * // First workflow execution
     * interpreter.readYaml(workflow1);
     * while (true) {
     *     ActionResult result = interpreter.execCode();
     *     if (result.getResult().contains("end")) break;
     * }
     *
     * // Reset and reuse for second workflow
     * interpreter.reset();
     * interpreter.readYaml(workflow2);
     * while (true) {
     *     ActionResult result = interpreter.execCode();
     *     if (result.getResult().contains("end")) break;
     * }
     * }</pre>
     *
     * @see ReusableSubWorkflowCaller
     * @since 2.5.0
     */
    public void reset() {
        this.currentRow = 0;
        this.currentState = "0";
        this.code = null;
    }

    /**
     * Sets the reference to the actor executing this interpreter.
     *
     * <p>This method is called by IIActorRef implementations to establish the
     * link between the interpreter and its actor wrapper, enabling Unix-style
     * path resolution within workflows.</p>
     *
     * @param actorRef the actor reference wrapping this interpreter
     */
    public void setSelfActorRef(IIActorRef<?> actorRef) {
        this.selfActorRef = actorRef;
    }


}
