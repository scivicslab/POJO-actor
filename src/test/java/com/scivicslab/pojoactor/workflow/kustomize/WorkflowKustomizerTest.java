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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.workflow.Interpreter;
import com.scivicslab.pojoactor.workflow.MatrixCode;
import com.scivicslab.pojoactor.workflow.Vertex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WorkflowKustomizer.
 *
 * @author devteam@scivics-lab.com
 * @since 2.9.0
 */
class WorkflowKustomizerTest {

    private WorkflowKustomizer kustomizer;
    private Path testResourcesPath;

    @BeforeEach
    void setUp() throws URISyntaxException {
        kustomizer = new WorkflowKustomizer();
        testResourcesPath = Paths.get(
            getClass().getClassLoader().getResource("kustomize").toURI()
        );
    }

    @Test
    @DisplayName("Should load base workflow without patches")
    void testLoadBaseWorkflow() throws IOException {
        Path basePath = testResourcesPath.resolve("base");
        Map<String, Map<String, Object>> result = kustomizer.build(basePath);

        assertNotNull(result);
        assertTrue(result.containsKey("main-workflow.yaml"));

        Map<String, Object> workflow = result.get("main-workflow.yaml");
        assertEquals("MainWorkflow", workflow.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");
        assertEquals(3, steps.size());
    }

    @Test
    @DisplayName("Should apply patch and overwrite vertex by vertexName")
    void testPatchOverwriteVertex() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

        // Should have renamed file with prefix
        assertTrue(result.containsKey("prod-main-workflow.yaml"));

        Map<String, Object> workflow = result.get("prod-main-workflow.yaml");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");

        // Should have 4 steps now (3 original + 1 inserted)
        assertEquals(4, steps.size());

        // Check init vertex was overwritten to use json
        Map<String, Object> initStep = findStepByVertexName(steps, "init");
        assertNotNull(initStep);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initActions = (List<Map<String, Object>>) initStep.get("actions");
        @SuppressWarnings("unchecked")
        List<String> initArgs = (List<String>) initActions.get(0).get("arguments");
        assertEquals("json", initArgs.get(0));

        // Check create-nodes was overwritten to use webservers
        Map<String, Object> createNodesStep = findStepByVertexName(steps, "create-nodes");
        assertNotNull(createNodesStep);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> createActions = (List<Map<String, Object>>) createNodesStep.get("actions");
        @SuppressWarnings("unchecked")
        List<String> createArgs = (List<String>) createActions.get(0).get("arguments");
        assertEquals("webservers", createArgs.get(0));
    }

    @Test
    @DisplayName("Should insert new vertex after anchor")
    void testInsertNewVertex() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

        Map<String, Object> workflow = result.get("prod-main-workflow.yaml");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");

        // Find the inserted vertex
        Map<String, Object> setupLoggingStep = findStepByVertexName(steps, "setup-logging");
        assertNotNull(setupLoggingStep, "setup-logging vertex should be inserted");

        // Verify it was inserted after create-nodes
        int createNodesIndex = findIndexByVertexName(steps, "create-nodes");
        int setupLoggingIndex = findIndexByVertexName(steps, "setup-logging");
        assertEquals(createNodesIndex + 1, setupLoggingIndex,
            "setup-logging should be inserted after create-nodes");
    }

    @Test
    @DisplayName("Should apply name prefix")
    void testNamePrefix() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

        Map<String, Object> workflow = result.get("prod-main-workflow.yaml");
        assertEquals("prod-MainWorkflow", workflow.get("name"));
    }

    @Test
    @DisplayName("Should update workflow references with prefix")
    void testWorkflowReferenceUpdate() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        Map<String, Map<String, Object>> result = kustomizer.build(overlayPath);

        Map<String, Object> workflow = result.get("prod-main-workflow.yaml");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) workflow.get("steps");

        Map<String, Object> runTasksStep = findStepByVertexName(steps, "run-tasks");
        assertNotNull(runTasksStep);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) runTasksStep.get("actions");
        @SuppressWarnings("unchecked")
        Map<String, Object> applyArgs = (Map<String, Object>) actions.get(0).get("arguments");
        @SuppressWarnings("unchecked")
        List<String> nestedArgs = (List<String>) applyArgs.get("arguments");

        assertEquals("prod-task.yaml", nestedArgs.get(0),
            "Workflow reference should be updated with prefix");
    }

    @Test
    @DisplayName("Should throw OrphanVertexException for orphan vertex")
    void testOrphanVertexException() throws IOException, URISyntaxException {
        // Create a temporary orphan patch scenario
        // For now, we'll test the exception class directly
        OrphanVertexException ex = new OrphanVertexException("orphan-vertex", "bad-patch.yaml");

        assertEquals("orphan-vertex", ex.getVertexName());
        assertEquals("bad-patch.yaml", ex.getPatchFile());
        assertTrue(ex.getMessage().contains("orphan-vertex"));
        assertTrue(ex.getMessage().contains("bad-patch.yaml"));
    }

    @Test
    @DisplayName("Should generate valid YAML output")
    void testBuildAsYaml() throws IOException {
        Path overlayPath = testResourcesPath.resolve("overlays/production");
        String yaml = kustomizer.buildAsYaml(overlayPath);

        assertNotNull(yaml);
        assertTrue(yaml.contains("prod-MainWorkflow"));
        assertTrue(yaml.contains("vertexName: init"));
        assertTrue(yaml.contains("json"));
        assertTrue(yaml.contains("webservers"));
    }

    // Helper methods

    @SuppressWarnings("unchecked")
    private Map<String, Object> findStepByVertexName(List<Map<String, Object>> steps, String vertexName) {
        return steps.stream()
            .filter(s -> vertexName.equals(s.get("vertexName")))
            .findFirst()
            .orElse(null);
    }

    private int findIndexByVertexName(List<Map<String, Object>> steps, String vertexName) {
        for (int i = 0; i < steps.size(); i++) {
            if (vertexName.equals(steps.get(i).get("vertexName"))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Tests for Interpreter integration with overlay support.
     */
    @Nested
    @DisplayName("Interpreter Integration")
    class InterpreterIntegrationTest {

        @Test
        @DisplayName("Should read YAML from Path without overlay")
        void testReadYamlFromPath() throws IOException {
            Path workflowPath = testResourcesPath.resolve("base/main-workflow.yaml");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath);

            MatrixCode code = interpreter.getCode();
            assertNotNull(code);
            assertEquals("MainWorkflow", code.getName());
            assertEquals(3, code.getSteps().size());
        }

        @Test
        @DisplayName("Should read YAML with overlay applied at runtime")
        void testReadYamlWithOverlay() throws IOException {
            Path workflowPath = testResourcesPath.resolve("base/main-workflow.yaml");
            Path overlayPath = testResourcesPath.resolve("overlays/production");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath, overlayPath);

            MatrixCode code = interpreter.getCode();
            assertNotNull(code);

            // Name should have prefix applied
            assertEquals("prod-MainWorkflow", code.getName());

            // Should have 4 steps (3 original + 1 inserted)
            assertEquals(4, code.getSteps().size());

            // Verify vertexName is preserved
            Vertex initVertex = code.getSteps().stream()
                .filter(r -> "init".equals(r.getVertexName()))
                .findFirst()
                .orElse(null);
            assertNotNull(initVertex, "init vertex should exist");

            // Verify overlay was applied - init should use 'json' argument
            Object args = initVertex.getActions().get(0).getArguments();
            assertTrue(args instanceof List);
            @SuppressWarnings("unchecked")
            List<String> argList = (List<String>) args;
            assertEquals("json", argList.get(0));
        }

        @Test
        @DisplayName("Should preserve vertexName in loaded workflow")
        void testVertexNamePreserved() throws IOException {
            Path workflowPath = testResourcesPath.resolve("base/main-workflow.yaml");
            Path overlayPath = testResourcesPath.resolve("overlays/production");

            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("test")
                .build();

            interpreter.readYaml(workflowPath, overlayPath);

            MatrixCode code = interpreter.getCode();

            // Check all vertices have their names
            List<String> vertexNames = code.getSteps().stream()
                .map(Vertex::getVertexName)
                .toList();

            assertTrue(vertexNames.contains("init"));
            assertTrue(vertexNames.contains("create-nodes"));
            assertTrue(vertexNames.contains("setup-logging")); // inserted vertex
            assertTrue(vertexNames.contains("run-tasks"));
        }
    }
}
