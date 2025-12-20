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
import java.util.List;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.scivicslab.pojoactor.ActionResult;

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

    Logger logger = null;

    MatrixCode code = null;

    int currentRow = 0;

    String currentState = "0";

    IIActorSystem system = null;

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
     * corresponding actor from the system and invokes the specified action
     * by name with the provided arguments.</p>
     *
     * @return an {@link ActionResult} indicating success or failure
     */
    public ActionResult action() {
        Row row = code.getSteps().get(currentRow);
        for (Action a: row.getActions()) {
            String actorName = a.getActor();
            String action    = a.getMethod();
            String argument  = a.getArgument();

            IIActorRef<?> actorAR = system.getIIActor(actorName);
            if (actorAR != null) {
                actorAR.callByActionName(action, argument);
            }


        }
        return new ActionResult(true, "");
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
                    action.setArgument(actionElement.getTextContent().trim());

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
     * @return an {@link ActionResult} indicating success or failure
     */
    public ActionResult execCode() {
        if (code == null || code.getSteps().isEmpty()) {
            return new ActionResult(false, "No code loaded");
        }

        Row row = code.getSteps().get(currentRow);
        List<String> states = row.getStates();

        if (states.size() >= 2 && states.get(0).equals(currentState)) {
            action();
            currentState = states.get(1);

            for (int i = 0; i < code.getSteps().size(); i++) {
                Row nextRow = code.getSteps().get(i);
                if (!nextRow.getStates().isEmpty() && nextRow.getStates().get(0).equals(currentState)) {
                    currentRow = i;
                    break;
                }
            }
            return new ActionResult(true, "State: " + currentState);
        }

        return new ActionResult(false, "No matching state transition");
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


}
