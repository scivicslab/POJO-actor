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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.plugin.MathPlugin;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;
import com.scivicslab.pojoactor.workflow.MatrixCode;
import com.scivicslab.pojoactor.workflow.Row;
import com.scivicslab.pojoactor.workflow.Action;

/**
 * Comprehensive tests for Workflow Interpreter functionality.
 *
 * <p>This test suite verifies the workflow execution engine, which reads
 * YAML/JSON/XML workflow definitions and executes them using CallableByActionName.</p>
 *
 * @author devteam@scivics-lab.com
 * @version 2.5.0
 */
@DisplayName("Workflow Interpreter Specification by Example")
public class WorkflowInterpreterTest {

    private IIActorSystem system;
    private MathPlugin mathPlugin;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("workflow-test-system");
        mathPlugin = new MathPlugin();

        // Register math actor
        TestMathIIAR mathActor = new TestMathIIAR("math", mathPlugin, system);
        system.addIIActor(mathActor);
    }

    /**
     * Test implementation of IIActorRef for MathPlugin.
     */
    private static class TestMathIIAR extends IIActorRef<MathPlugin> {

        public TestMathIIAR(String actorName, MathPlugin object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return this.object.callByActionName(actionName, args);
        }
    }

    /**
     * Example 1: Load YAML workflow definition.
     */
    @Test
    @DisplayName("Should load workflow from YAML")
    public void testLoadWorkflowFromYaml() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        assertNotNull(yamlInput, "YAML resource should exist");

        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();
        assertNotNull(code, "Code should be loaded");
        assertEquals("simple-math-workflow", code.getName());
        assertEquals(3, code.getSteps().size(), "Should have 3 rows");
    }

    /**
     * Example 2: Execute single-step workflow.
     */
    @Test
    @DisplayName("Should execute single-step workflow")
    public void testExecuteSingleStepWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        // Execute first step: state 0 -> 1, action: add 10,5
        ActionResult result = interpreter.execCode();

        assertTrue(result.isSuccess(), "Step should succeed");
        assertTrue(result.getResult().contains("State: 1"), "Should transition to state 1");

        // Verify the action was executed
        assertEquals(15, mathPlugin.getLastResult(), "Math operation should have been executed");
    }

    /**
     * Example 3: Execute multi-step workflow.
     */
    @Test
    @DisplayName("Should execute multi-step workflow with state transitions")
    public void testExecuteMultiStepWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        // Step 1: 0 -> 1, add 10,5 (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply 3,4 (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 4: Execute workflow with multiple actions in one step.
     */
    @Test
    @DisplayName("Should execute multiple actions in a single workflow step")
    public void testExecuteMultipleActionsInOneStep() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/multi-action.yaml");
        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();
        assertEquals("multi-action-workflow", code.getName());

        Row firstRow = code.getSteps().get(0);
        assertEquals(3, firstRow.getActions().size(), "First row should have 3 actions");

        // Execute the step with multiple actions
        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess());

        // The last action (getLastResult) doesn't change the result,
        // so we check the result of multiply (2,4)
        assertEquals(8, mathPlugin.getLastResult());
    }

    /**
     * Example 5: Verify workflow matrix structure.
     */
    @Test
    @DisplayName("Should parse workflow matrix structure correctly")
    public void testWorkflowMatrixStructure() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();

        // Check first row
        Row row0 = code.getSteps().get(0);
        assertEquals(2, row0.getStates().size());
        assertEquals("0", row0.getStates().get(0));
        assertEquals("1", row0.getStates().get(1));
        assertEquals(1, row0.getActions().size());
        assertEquals("math", row0.getActions().get(0).getActor());
        assertEquals("add", row0.getActions().get(0).getMethod());
        assertEquals("10,5", row0.getActions().get(0).getArgument());

        // Check second row
        Row row1 = code.getSteps().get(1);
        assertEquals("1", row1.getStates().get(0));
        assertEquals("2", row1.getStates().get(1));
        assertEquals("multiply", row1.getActions().get(0).getMethod());
    }

    /**
     * Example 6: Handle missing actor.
     */
    @Test
    @DisplayName("Should handle missing actor gracefully")
    public void testHandleMissingActor() {
        // Create workflow with reference to non-existent actor
        MatrixCode code = new MatrixCode();
        code.setName("test-missing-actor");

        Row row = new Row();
        row.setStates(java.util.Arrays.asList("0", "1"));
        Action action = new Action();
        action.setActor("nonexistent");
        action.setMethod("someAction");
        action.setArgument("args");
        row.setActions(java.util.Arrays.asList(action));
        code.setSteps(java.util.Arrays.asList(row));

        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Manually set the code (bypass YAML loading)
        java.lang.reflect.Field codeField;
        try {
            codeField = Interpreter.class.getDeclaredField("code");
            codeField.setAccessible(true);
            codeField.set(interpreter, code);
        } catch (Exception e) {
            fail("Failed to set code field: " + e.getMessage());
        }

        // Execute should not throw exception (actor is null, so action is skipped)
        ActionResult result = interpreter.action();
        assertTrue(result.isSuccess(), "Should succeed even with missing actor");
    }

    /**
     * Example 7: Empty workflow code.
     */
    @Test
    @DisplayName("Should handle empty workflow code")
    public void testEmptyWorkflowCode() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        // Don't load any code
        ActionResult result = interpreter.execCode();

        assertFalse(result.isSuccess(), "Empty code should fail");
        assertEquals("No code loaded", result.getResult());
    }

    /**
     * Example 8: Builder pattern for Interpreter construction.
     */
    @Test
    @DisplayName("Should construct Interpreter using Builder pattern")
    public void testInterpreterBuilder() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("custom-logger")
                .team(system)
                .build();

        assertNotNull(interpreter, "Interpreter should be created");
    }

    /**
     * Example 9: State transition validation.
     */
    @Test
    @DisplayName("Should validate state transitions")
    public void testStateTransitionValidation() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        // Execute first step
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertTrue(result1.getResult().contains("State: 1"));

        // Execute second step
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertTrue(result2.getResult().contains("State: 2"));
    }

    /**
     * Example 10: Workflow with end state.
     */
    @Test
    @DisplayName("Should handle workflow end state")
    public void testWorkflowEndState() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/simple-math.yaml");
        interpreter.readYaml(yamlInput);

        // Execute all steps
        interpreter.execCode();  // 0 -> 1
        interpreter.execCode();  // 1 -> 2
        ActionResult result = interpreter.execCode();  // 2 -> end

        assertTrue(result.isSuccess());
        assertTrue(result.getResult().contains("State: end"));
    }

    // ==================== XML Workflow Tests ====================

    /**
     * Example 11: Load XML workflow definition.
     */
    @Test
    @DisplayName("Should load workflow from XML")
    public void testLoadWorkflowFromXml() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        assertNotNull(xmlInput, "XML resource should exist");

        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertNotNull(code, "Code should be loaded");
        assertEquals("simple-math-workflow", code.getName());
        assertEquals(3, code.getSteps().size(), "Should have 3 rows");
    }

    /**
     * Example 12: Execute XML workflow with single step.
     */
    @Test
    @DisplayName("Should execute XML workflow single step")
    public void testExecuteXmlSingleStepWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        // Execute first step: state 0 -> 1, action: add 10,5
        ActionResult result = interpreter.execCode();

        assertTrue(result.isSuccess(), "Step should succeed");
        assertTrue(result.getResult().contains("State: 1"), "Should transition to state 1");

        // Verify the action was executed
        assertEquals(15, mathPlugin.getLastResult(), "Math operation should have been executed");
    }

    /**
     * Example 13: Execute XML multi-step workflow.
     */
    @Test
    @DisplayName("Should execute XML multi-step workflow with state transitions")
    public void testExecuteXmlMultiStepWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        // Step 1: 0 -> 1, add 10,5 (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply 3,4 (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 14: Execute XML workflow with multiple actions in one step.
     */
    @Test
    @DisplayName("Should execute multiple actions in a single XML workflow step")
    public void testExecuteXmlMultipleActionsInOneStep() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/multi-action.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertEquals("multi-action-workflow", code.getName());

        Row firstRow = code.getSteps().get(0);
        assertEquals(3, firstRow.getActions().size(), "First row should have 3 actions");

        // Execute the step with multiple actions
        ActionResult result = interpreter.execCode();
        assertTrue(result.isSuccess());

        // The last action (getLastResult) doesn't change the result,
        // so we check the result of multiply (2,4)
        assertEquals(8, mathPlugin.getLastResult());
    }

    /**
     * Example 15: Verify XML workflow matrix structure.
     */
    @Test
    @DisplayName("Should parse XML workflow matrix structure correctly")
    public void testXmlWorkflowMatrixStructure() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();

        // Check first row
        Row row0 = code.getSteps().get(0);
        assertEquals(2, row0.getStates().size());
        assertEquals("0", row0.getStates().get(0));
        assertEquals("1", row0.getStates().get(1));
        assertEquals(1, row0.getActions().size());
        assertEquals("math", row0.getActions().get(0).getActor());
        assertEquals("add", row0.getActions().get(0).getMethod());
        assertEquals("10,5", row0.getActions().get(0).getArgument());

        // Check second row
        Row row1 = code.getSteps().get(1);
        assertEquals("1", row1.getStates().get(0));
        assertEquals("2", row1.getStates().get(1));
        assertEquals("multiply", row1.getActions().get(0).getMethod());
    }

    /**
     * Example 16: XML workflow with complex branching.
     */
    @Test
    @DisplayName("Should load complex branching XML workflow")
    public void testComplexBranchingXmlWorkflow() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/complex-branching.xml");
        assertNotNull(xmlInput, "complex-branching.xml should exist");

        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertNotNull(code, "Code should be loaded");
        assertEquals("complex-branching", code.getName());
        assertEquals(16, code.getSteps().size(), "Should have 16 transitions");

        // Verify first transition
        Row firstRow = code.getSteps().get(0);
        assertEquals("init", firstRow.getStates().get(0));
        assertEquals("state_A", firstRow.getStates().get(1));
        assertEquals("checker", firstRow.getActions().get(0).getActor());
        assertEquals("check_condition1", firstRow.getActions().get(0).getMethod());
    }

    /**
     * Example 17: Empty argument in XML action.
     */
    @Test
    @DisplayName("Should handle empty arguments in XML actions")
    public void testXmlEmptyArgument() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/simple-math.xml");
        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        Row lastRow = code.getSteps().get(2);  // The last row has getLastResult with empty argument

        assertEquals("", lastRow.getActions().get(0).getArgument(), "Empty argument should be empty string");
    }

    // ==================== New Arguments Format Tests ====================

    /**
     * Example 18: Load YAML workflow with arguments list format.
     */
    @Test
    @DisplayName("Should load and execute YAML workflow with arguments list format")
    public void testYamlWithArgumentsListFormat() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/arguments-list-format.yaml");
        assertNotNull(yamlInput, "arguments-list-format.yaml should exist");

        interpreter.readYaml(yamlInput);

        MatrixCode code = interpreter.getCode();
        assertNotNull(code);
        assertEquals("arguments-list-format-workflow", code.getName());

        // Step 1: 0 -> 1, add ["10", "5"] (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply ["3", "4"] (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult [] (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 19: Load JSON workflow with arguments list format.
     */
    @Test
    @DisplayName("Should load and execute JSON workflow with arguments list format")
    public void testJsonWithArgumentsListFormat() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream jsonInput = getClass().getResourceAsStream("/workflows/arguments-list-format.json");
        assertNotNull(jsonInput, "arguments-list-format.json should exist");

        try {
            interpreter.readJson(jsonInput);
        } catch (Exception e) {
            fail("Failed to read JSON workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertNotNull(code);
        assertEquals("arguments-list-format-workflow", code.getName());

        // Step 1: 0 -> 1, add ["10", "5"] (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply ["3", "4"] (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult [] (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 20: Load XML workflow with arguments list format.
     */
    @Test
    @DisplayName("Should load and execute XML workflow with arguments list format")
    public void testXmlWithArgumentsListFormat() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream xmlInput = getClass().getResourceAsStream("/workflows/arguments-list-format.xml");
        assertNotNull(xmlInput, "arguments-list-format.xml should exist");

        try {
            interpreter.readXml(xmlInput);
        } catch (Exception e) {
            fail("Failed to read XML workflow: " + e.getMessage());
        }

        MatrixCode code = interpreter.getCode();
        assertNotNull(code);
        assertEquals("arguments-list-format-workflow", code.getName());

        // Step 1: 0 -> 1, add ["10", "5"] (result: 15)
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 2: 1 -> 2, multiply ["3", "4"] (result: 12)
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());

        // Step 3: 2 -> end, getLastResult [] (result: 12)
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(12, mathPlugin.getLastResult());
    }

    /**
     * Example 21: Load YAML workflow with mixed arguments format (string + array).
     * Demonstrates that both string and array formats are supported.
     */
    @Test
    @DisplayName("Should load and execute YAML workflow with mixed arguments format")
    public void testYamlWithMixedArguments() {
        Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test-interpreter")
                .team(system)
                .build();

        InputStream yamlInput = getClass().getResourceAsStream("/workflows/arguments-mixed-format.yaml");
        assertNotNull(yamlInput, "arguments-mixed-format.yaml should exist");

        interpreter.readYaml(yamlInput);
        MatrixCode code = interpreter.getCode();
        assertNotNull(code);
        assertEquals("arguments-mixed-format-workflow", code.getName());

        // Verify workflow loaded correctly with mixed argument formats
        assertEquals(3, code.getSteps().size());

        // Step 1: greet with string format (no array brackets) - action executed but result in state
        ActionResult result1 = interpreter.execCode();
        assertTrue(result1.isSuccess());

        // Step 2: add with array format
        ActionResult result2 = interpreter.execCode();
        assertTrue(result2.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());

        // Step 3: getLastResult with empty string
        ActionResult result3 = interpreter.execCode();
        assertTrue(result3.isSuccess());
        assertEquals(15, mathPlugin.getLastResult());
    }
}
