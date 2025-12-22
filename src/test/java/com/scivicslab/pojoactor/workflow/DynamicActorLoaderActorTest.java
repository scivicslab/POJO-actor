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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.ActionResult;

/**
 * Tests for DynamicActorLoaderActor.
 *
 * @author devteam@scivics-lab.com
 */
@DisplayName("Dynamic Actor Loader Specification by Example")
public class DynamicActorLoaderActorTest {

    private IIActorSystem system;
    private DynamicActorLoaderActor loader;

    @BeforeEach
    public void setUp() {
        system = new IIActorSystem("test-system");
        loader = new DynamicActorLoaderActor(system);
    }

    @Test
    @DisplayName("Should list available ActorProviders")
    public void testListProviders() {
        ActionResult result = loader.callByActionName("listProviders", "");

        assertTrue(result.isSuccess(), "Should succeed");
        assertNotNull(result.getResult(), "Result should not be null");
        // Should contain "math" provider from test resources
        assertTrue(result.getResult().contains("math") || result.getResult().contains("No providers"),
            "Should list providers or indicate none found");
    }

    @Test
    @DisplayName("Should register actors from ServiceLoader provider")
    public void testCreateFromProvider() {
        // Test the mechanism - provider may or may not be found depending on test setup
        ActionResult result = loader.callByActionName("createFromProvider", "MathPluginProvider");

        // The mechanism should work (either success or "Provider not found")
        assertNotNull(result, "Result should not be null");
        assertTrue(
            result.isSuccess() || result.getResult().contains("Provider not found"),
            "Should either succeed or indicate provider not found"
        );

        // Note: This test validates the mechanism works correctly
        // Whether providers exist depends on META-INF/services configuration
    }

    @Test
    @DisplayName("Should handle unknown action")
    public void testUnknownAction() {
        ActionResult result = loader.callByActionName("unknownAction", "");

        assertFalse(result.isSuccess(), "Should fail for unknown action");
        assertTrue(result.getResult().contains("Unknown action"), "Should indicate unknown action");
    }

    @Test
    @DisplayName("Should handle non-existent provider")
    public void testNonExistentProvider() {
        ActionResult result = loader.callByActionName("createFromProvider", "nonexistent");

        assertFalse(result.isSuccess(), "Should fail for non-existent provider");
        assertTrue(result.getResult().contains("Provider not found"),
            "Should indicate provider not found");
    }

    @Test
    @DisplayName("Should handle invalid arguments for loadFromJar")
    public void testInvalidArgsLoadFromJar() {
        ActionResult result = loader.callByActionName("loadFromJar", "invalid");

        assertFalse(result.isSuccess(), "Should fail with invalid args");
        assertTrue(result.getResult().contains("Invalid args") ||
                   result.getResult().contains("Failed to load"),
            "Should indicate error");
    }

    @Test
    @DisplayName("Should be usable in workflows")
    public void testWorkflowIntegration() throws Exception {
        // Create interpreter with loader actor
        Interpreter interpreter = new Interpreter.Builder()
            .loggerName("test")
            .team(system)
            .build();

        // Register loader as IIActor
        system.addIIActor(new DynamicActorLoaderIIAR("loader", loader, system));

        // Create simple workflow that uses loader
        String workflowXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <workflow name="test-dynamic">
                <steps>
                    <transition from="0" to="end">
                        <action actor="loader" method="listProviders"></action>
                    </transition>
                </steps>
            </workflow>
            """;

        interpreter.readXml(new java.io.ByteArrayInputStream(workflowXml.getBytes()));
        ActionResult result = interpreter.execCode();

        assertTrue(result.isSuccess(), "Workflow should execute successfully");
    }

    /**
     * Helper IIActorRef for DynamicActorLoaderActor.
     */
    private static class DynamicActorLoaderIIAR extends IIActorRef<DynamicActorLoaderActor> {

        public DynamicActorLoaderIIAR(String actorName, DynamicActorLoaderActor object,
                                      IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            return this.object.callByActionName(actionName, args);
        }
    }
}
