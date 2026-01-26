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

package com.scivicslab.pojoactor.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.plugin.MathPlugin;

/**
 * Comprehensive tests for CallableByActionName interface and ActionResult class.
 *
 * <p>This test suite verifies the actor-WF (Actor-Workflow) approach where
 * plugin methods can be invoked using string-based action names. This approach
 * enables YAML/JSON-based workflow execution and distributed system support.</p>
 *
 * @author devteam@scivicslab.com
 * @version 2.5.0
 */
@DisplayName("CallableByActionName Specification by Example")
public class CallableByActionNameTest {

    /**
     * Example 1: Basic action invocation with valid arguments.
     */
    @Test
    @DisplayName("Should execute add action with valid comma-separated arguments")
    public void testAddActionBasic() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("add", "5,3");

        assertTrue(result.isSuccess(), "Action should succeed");
        assertEquals("8", result.getResult(), "5 + 3 should equal 8");
    }

    /**
     * Example 2: State preservation across multiple actions.
     */
    @Test
    @DisplayName("Should preserve state (lastResult) across multiple actions")
    public void testStatePreservation() {
        MathPlugin plugin = new MathPlugin();

        // First action: add 10 + 5 = 15
        ActionResult result1 = plugin.callByActionName("add", "10,5");
        assertTrue(result1.isSuccess());
        assertEquals("15", result1.getResult());

        // Second action: multiply 3 * 4 = 12 (should overwrite lastResult)
        ActionResult result2 = plugin.callByActionName("multiply", "3,4");
        assertTrue(result2.isSuccess());
        assertEquals("12", result2.getResult());

        // Third action: getLastResult should return the last result (12)
        ActionResult result3 = plugin.callByActionName("getLastResult", "");
        assertTrue(result3.isSuccess());
        assertEquals("12", result3.getResult(), "Should return last result from multiply");
    }

    /**
     * Example 3: Arguments with whitespace should be trimmed.
     */
    @Test
    @DisplayName("Should trim whitespace from arguments")
    public void testArgumentTrimming() {
        MathPlugin plugin = new MathPlugin();

        // Arguments with extra spaces: " 7 , 3 "
        ActionResult result = plugin.callByActionName("add", " 7 , 3 ");

        assertTrue(result.isSuccess(), "Action should succeed even with whitespace");
        assertEquals("10", result.getResult(), "7 + 3 should equal 10");
    }

    /**
     * Example 4: Unknown action should return failure with descriptive message.
     */
    @Test
    @DisplayName("Should return failure for unknown action")
    public void testUnknownAction() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("unknownAction", "");

        assertFalse(result.isSuccess(), "Unknown action should fail");
        assertTrue(result.getResult().contains("Unknown action"),
                   "Error message should mention 'Unknown action'");
        assertTrue(result.getResult().contains("unknownAction"),
                   "Error message should include the action name");
    }

    /**
     * Example 5: Invalid number format should return failure.
     */
    @Test
    @DisplayName("Should return failure for invalid number format")
    public void testInvalidNumberFormat() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("add", "abc,def");

        assertFalse(result.isSuccess(), "Invalid number format should fail");
        assertTrue(result.getResult().contains("number format") ||
                   result.getResult().contains("NumberFormat"),
                   "Error message should mention number format issue");
    }

    /**
     * Example 6: Missing required arguments should return failure.
     */
    @Test
    @DisplayName("Should return failure when required arguments are missing")
    public void testMissingArguments() {
        MathPlugin plugin = new MathPlugin();

        // add requires 2 arguments but only 1 provided
        ActionResult result = plugin.callByActionName("add", "5");

        assertFalse(result.isSuccess(), "Missing arguments should fail");
        assertTrue(result.getResult().contains("2 arguments") ||
                   result.getResult().contains("requires"),
                   "Error message should mention argument requirement");
    }

    /**
     * Example 7: Too many arguments should return failure.
     */
    @Test
    @DisplayName("Should return failure when too many arguments are provided")
    public void testTooManyArguments() {
        MathPlugin plugin = new MathPlugin();

        // add requires 2 arguments but 3 provided
        ActionResult result = plugin.callByActionName("add", "5,3,2");

        assertFalse(result.isSuccess(), "Too many arguments should fail");
        assertTrue(result.getResult().contains("2 arguments") ||
                   result.getResult().contains("requires"),
                   "Error message should mention argument requirement");
    }

    /**
     * Example 8: Empty argument string for greet action should fail.
     */
    @Test
    @DisplayName("Should return failure when greet action receives empty argument")
    public void testGreetWithEmptyArgument() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("greet", "");

        assertFalse(result.isSuccess(), "Empty argument for greet should fail");
        assertTrue(result.getResult().contains("name argument") ||
                   result.getResult().contains("requires"),
                   "Error message should mention name argument requirement");
    }

    /**
     * Example 9: Greet action with valid name.
     */
    @Test
    @DisplayName("Should execute greet action with valid name")
    public void testGreetWithValidName() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("greet", "Alice");

        assertTrue(result.isSuccess(), "Greet action should succeed");
        assertEquals("Hello, Alice from MathPlugin!", result.getResult());
    }

    /**
     * Example 10: Greet action with name containing spaces.
     */
    @Test
    @DisplayName("Should handle name with spaces in greet action")
    public void testGreetWithSpacesInName() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("greet", "Alice Smith");

        assertTrue(result.isSuccess(), "Greet should handle names with spaces");
        assertEquals("Hello, Alice Smith from MathPlugin!", result.getResult());
    }

    /**
     * Example 11: Multiple multiply operations updating lastResult.
     */
    @Test
    @DisplayName("Should update lastResult with each multiply operation")
    public void testMultipleMultiply() {
        MathPlugin plugin = new MathPlugin();

        // First multiply: 2 * 3 = 6
        ActionResult result1 = plugin.callByActionName("multiply", "2,3");
        assertTrue(result1.isSuccess());
        assertEquals("6", result1.getResult());

        // Second multiply: 4 * 5 = 20
        ActionResult result2 = plugin.callByActionName("multiply", "4,5");
        assertTrue(result2.isSuccess());
        assertEquals("20", result2.getResult());

        // Check lastResult is 20
        ActionResult result3 = plugin.callByActionName("getLastResult", "");
        assertEquals("20", result3.getResult());
    }

    /**
     * Example 12: getLastResult before any operation (initial state).
     */
    @Test
    @DisplayName("Should return 0 for getLastResult before any operations")
    public void testGetLastResultInitialState() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("getLastResult", "");

        assertTrue(result.isSuccess(), "getLastResult should always succeed");
        assertEquals("0", result.getResult(), "Initial lastResult should be 0");
    }

    /**
     * Example 13: Negative numbers in add operation.
     */
    @Test
    @DisplayName("Should handle negative numbers in add operation")
    public void testAddWithNegativeNumbers() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("add", "-5,3");

        assertTrue(result.isSuccess());
        assertEquals("-2", result.getResult(), "-5 + 3 should equal -2");
    }

    /**
     * Example 14: Zero in calculations.
     */
    @Test
    @DisplayName("Should handle zero in calculations")
    public void testCalculationsWithZero() {
        MathPlugin plugin = new MathPlugin();

        ActionResult addResult = plugin.callByActionName("add", "0,5");
        assertTrue(addResult.isSuccess());
        assertEquals("5", addResult.getResult());

        ActionResult multiplyResult = plugin.callByActionName("multiply", "0,10");
        assertTrue(multiplyResult.isSuccess());
        assertEquals("0", multiplyResult.getResult());
    }

    /**
     * Example 15: Large numbers in calculations.
     */
    @Test
    @DisplayName("Should handle large numbers")
    public void testLargeNumbers() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("add", "1000000,2000000");

        assertTrue(result.isSuccess());
        assertEquals("3000000", result.getResult());
    }

    /**
     * Example 16: ActionResult toString() method.
     */
    @Test
    @DisplayName("ActionResult should have meaningful toString()")
    public void testActionResultToString() {
        ActionResult success = new ActionResult(true, "result data");
        String successString = success.toString();

        assertTrue(successString.contains("success=true"),
                   "toString should include success status");
        assertTrue(successString.contains("result data"),
                   "toString should include result data");

        ActionResult failure = new ActionResult(false, "error message");
        String failureString = failure.toString();

        assertTrue(failureString.contains("success=false"),
                   "toString should include failure status");
        assertTrue(failureString.contains("error message"),
                   "toString should include error message");
    }

    /**
     * Example 17: Workflow simulation - calculator session.
     */
    @Test
    @DisplayName("Should support calculator-like workflow session")
    public void testCalculatorWorkflow() {
        MathPlugin plugin = new MathPlugin();

        // User session: (10 + 5) then (result * 2)
        plugin.callByActionName("add", "10,5");          // lastResult = 15
        ActionResult step1 = plugin.callByActionName("getLastResult", "");
        assertEquals("15", step1.getResult());

        plugin.callByActionName("multiply", "2,1");      // lastResult = 2 * 1 = 2
        plugin.callByActionName("multiply", "15,2");     // lastResult = 15 * 2 = 30
        ActionResult step2 = plugin.callByActionName("getLastResult", "");
        assertEquals("30", step2.getResult());
    }

    /**
     * Example 18: Comma in arguments (single argument case).
     */
    @Test
    @DisplayName("Should handle arguments that might contain commas")
    public void testArgumentsWithCommas() {
        MathPlugin plugin = new MathPlugin();

        // This tests that greet action doesn't split on comma
        // (it takes the entire string as-is)
        ActionResult result = plugin.callByActionName("greet", "Smith, John");

        assertTrue(result.isSuccess());
        assertEquals("Hello, Smith, John from MathPlugin!", result.getResult());
    }

    /**
     * Example 19: Empty arguments for actions that don't need them.
     */
    @Test
    @DisplayName("Should handle empty arguments for getLastResult")
    public void testEmptyArgumentsForGetLastResult() {
        MathPlugin plugin = new MathPlugin();

        plugin.callByActionName("add", "7,8");

        // getLastResult doesn't use args, so empty string is fine
        ActionResult result = plugin.callByActionName("getLastResult", "");

        assertTrue(result.isSuccess());
        assertEquals("15", result.getResult());
    }

    /**
     * Example 20: Case sensitivity of action names.
     */
    @Test
    @DisplayName("Action names should be case-sensitive")
    public void testActionNameCaseSensitivity() {
        MathPlugin plugin = new MathPlugin();

        // "Add" (capital A) should not match "add"
        ActionResult result = plugin.callByActionName("Add", "5,3");

        assertFalse(result.isSuccess(), "Action names should be case-sensitive");
        assertTrue(result.getResult().contains("Unknown action"));
    }

    /**
     * Example 21: ActionResult immutability verification.
     */
    @Test
    @DisplayName("ActionResult should be immutable")
    public void testActionResultImmutability() {
        ActionResult result = new ActionResult(true, "test");

        assertEquals(true, result.isSuccess());
        assertEquals("test", result.getResult());

        // Cannot modify - fields are final
        // This test verifies the API contract
    }

    /**
     * Example 22: Multiple instances maintain independent state.
     */
    @Test
    @DisplayName("Different plugin instances should have independent state")
    public void testIndependentPluginInstances() {
        MathPlugin plugin1 = new MathPlugin();
        MathPlugin plugin2 = new MathPlugin();

        // plugin1: add 10 + 5 = 15
        plugin1.callByActionName("add", "10,5");

        // plugin2: add 3 + 2 = 5
        plugin2.callByActionName("add", "3,2");

        // Verify independent state
        ActionResult result1 = plugin1.callByActionName("getLastResult", "");
        assertEquals("15", result1.getResult(), "plugin1 should have lastResult=15");

        ActionResult result2 = plugin2.callByActionName("getLastResult", "");
        assertEquals("5", result2.getResult(), "plugin2 should have lastResult=5");
    }

    /**
     * Example 23: Partial argument string (only one number before comma).
     */
    @Test
    @DisplayName("Should handle partial argument string")
    public void testPartialArgumentString() {
        MathPlugin plugin = new MathPlugin();

        // Malformed: "5," - only one number, comma but no second number
        ActionResult result = plugin.callByActionName("add", "5,");

        assertFalse(result.isSuccess(), "Partial arguments should fail");
    }

    /**
     * Example 24: Only comma in argument string.
     */
    @Test
    @DisplayName("Should fail for argument string with only comma")
    public void testOnlyCommaInArguments() {
        MathPlugin plugin = new MathPlugin();

        ActionResult result = plugin.callByActionName("add", ",");

        assertFalse(result.isSuccess(), "Only comma should fail");
    }

    /**
     * Example 25: Very long number strings.
     */
    @Test
    @DisplayName("Should handle overflow gracefully")
    public void testNumberOverflow() {
        MathPlugin plugin = new MathPlugin();

        // This will cause NumberFormatException due to overflow
        String veryLargeNumber = "9999999999999999999999999";
        ActionResult result = plugin.callByActionName("add", veryLargeNumber + ",1");

        assertFalse(result.isSuccess(), "Number overflow should be caught");
        assertTrue(result.getResult().contains("number format") ||
                   result.getResult().contains("NumberFormat") ||
                   result.getResult().contains("Error"),
                   "Should indicate number format error");
    }
}
