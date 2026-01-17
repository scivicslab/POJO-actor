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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test for dynamically loading the log-analyzer-plugin.
 */
@DisplayName("LogAnalyzerPlugin dynamic loading test")
public class LogAnalyzerPluginTest {

    private static final Path PLUGIN_JAR = Paths.get(
        "../log-analyzer-plugin/target/log-analyzer-plugin-1.0.0.jar"
    );

    private static final String PLUGIN_CLASS =
        "com.scivicslab.plugin.loganalyzer.LogAnalyzerPlugin";

    @Test
    @DisplayName("Should load LogAnalyzerPlugin from JAR")
    public void testLoadPlugin() throws Exception {
        // Skip if JAR doesn't exist
        if (!PLUGIN_JAR.toFile().exists()) {
            System.out.println("Plugin JAR not found, skipping test: " + PLUGIN_JAR);
            return;
        }

        // Load the plugin
        ActorRef<Object> analyzer = DynamicActorLoader.loadActor(
            PLUGIN_JAR,
            PLUGIN_CLASS,
            "analyzer"
        );

        assertNotNull(analyzer, "Plugin should be loaded");

        // Test that it implements CallableByActionName
        ActionResult result = analyzer.ask(obj -> {
            if (obj instanceof CallableByActionName callable) {
                return callable.callByActionName("list-sessions", "");
            }
            return new ActionResult(false, "Not CallableByActionName");
        }).get();

        // Should fail with "Not connected" since we haven't connected to a DB
        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("Not connected"));

        analyzer.close();
    }

    @Test
    @DisplayName("Should connect to H2 database and list sessions")
    public void testConnectAndListSessions() throws Exception {
        // Skip if JAR doesn't exist
        if (!PLUGIN_JAR.toFile().exists()) {
            System.out.println("Plugin JAR not found, skipping test: " + PLUGIN_JAR);
            return;
        }

        // Use testcluster-iac logs if they exist
        Path testDb = Paths.get("/home/devteam/works/testcluster-iac/actor-iac-logs");
        if (!testDb.toFile().exists() && !Paths.get(testDb + ".mv.db").toFile().exists()) {
            System.out.println("Test database not found, skipping: " + testDb);
            return;
        }

        // Load the plugin
        ActorRef<Object> analyzer = DynamicActorLoader.loadActor(
            PLUGIN_JAR,
            PLUGIN_CLASS,
            "analyzer"
        );

        // Connect to database
        ActionResult connectResult = analyzer.ask(obj -> {
            if (obj instanceof CallableByActionName callable) {
                return callable.callByActionName("connect", testDb.toString());
            }
            return new ActionResult(false, "Not CallableByActionName");
        }).get();

        assertTrue(connectResult.isSuccess(), "Should connect: " + connectResult.getResult());

        // List sessions
        ActionResult listResult = analyzer.ask(obj -> {
            if (obj instanceof CallableByActionName callable) {
                return callable.callByActionName("list-sessions", "");
            }
            return new ActionResult(false, "Not CallableByActionName");
        }).get();

        assertTrue(listResult.isSuccess(), "Should list sessions");
        System.out.println("Sessions:\n" + listResult.getResult());

        // Disconnect
        analyzer.ask(obj -> {
            if (obj instanceof CallableByActionName callable) {
                return callable.callByActionName("disconnect", "");
            }
            return new ActionResult(false, "Not CallableByActionName");
        }).get();

        analyzer.close();
    }

    @Test
    @DisplayName("Should summarize disk info from logs")
    public void testSummarizeDisks() throws Exception {
        // Skip if JAR doesn't exist
        if (!PLUGIN_JAR.toFile().exists()) {
            System.out.println("Plugin JAR not found, skipping test: " + PLUGIN_JAR);
            return;
        }

        // Use testcluster-iac logs if they exist
        Path testDb = Paths.get("/home/devteam/works/testcluster-iac/actor-iac-logs");
        if (!testDb.toFile().exists() && !Paths.get(testDb + ".mv.db").toFile().exists()) {
            System.out.println("Test database not found, skipping: " + testDb);
            return;
        }

        // Load the plugin
        ActorRef<Object> analyzer = DynamicActorLoader.loadActor(
            PLUGIN_JAR,
            PLUGIN_CLASS,
            "analyzer"
        );

        // Connect
        analyzer.ask(obj -> {
            if (obj instanceof CallableByActionName callable) {
                return callable.callByActionName("connect", testDb.toString());
            }
            return new ActionResult(false, "Not CallableByActionName");
        }).get();

        // Summarize disks for session 2
        ActionResult diskResult = analyzer.ask(obj -> {
            if (obj instanceof CallableByActionName callable) {
                return callable.callByActionName("summarize-disks", "2");
            }
            return new ActionResult(false, "Not CallableByActionName");
        }).get();

        System.out.println("Disk Summary:\n" + diskResult.getResult());

        // Disconnect
        analyzer.ask(obj -> {
            if (obj instanceof CallableByActionName callable) {
                return callable.callByActionName("disconnect", "");
            }
            return new ActionResult(false, "Not CallableByActionName");
        }).get();

        analyzer.close();
    }
}
