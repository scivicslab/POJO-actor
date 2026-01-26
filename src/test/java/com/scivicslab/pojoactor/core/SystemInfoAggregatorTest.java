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

import com.scivicslab.pojoactor.core.testplugin.DummyPlugin;
import com.scivicslab.pojoactor.workflow.ActorSystemAware;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Test for dynamically loading plugins that implement CallableByActionName.
 *
 * <p>This test uses DummyPlugin from the test classpath instead of
 * external JAR files, ensuring POJO-actor has no external dependencies
 * for its unit tests.</p>
 */
@DisplayName("DummyPlugin dynamic loading test")
public class SystemInfoAggregatorTest {

    private static final String PLUGIN_CLASS =
        "com.scivicslab.pojoactor.core.testplugin.DummyPlugin";

    @Test
    @DisplayName("Should load DummyPlugin from classpath")
    public void testLoadPlugin() throws Exception {
        // Load the plugin class from classpath
        Class<?> clazz = Class.forName(PLUGIN_CLASS);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        assertNotNull(instance, "Plugin instance should not be null");
        assertTrue(instance instanceof CallableByActionName,
            "Plugin should implement CallableByActionName");

        // Test calling an action
        CallableByActionName callable = (CallableByActionName) instance;
        ActionResult result = callable.callByActionName("echo", "hello");

        assertTrue(result.isSuccess(), "Echo action should succeed");
        assertEquals("Echo: hello", result.getResult(),
            "Echo action should return the input");
    }

    @Test
    @DisplayName("Should return error when calling list-items without connecting")
    public void testListItemsWithoutConnect() throws Exception {
        Class<?> clazz = Class.forName(PLUGIN_CLASS);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        CallableByActionName callable = (CallableByActionName) instance;
        ActionResult result = callable.callByActionName("list-items", "");

        assertFalse(result.isSuccess(), "list-items should fail when not connected");
        assertTrue(result.getResult().contains("Not connected"),
            "Error message should contain 'Not connected'");
    }

    @Test
    @DisplayName("Should connect and list items successfully")
    public void testConnectAndListItems() throws Exception {
        Class<?> clazz = Class.forName(PLUGIN_CLASS);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        CallableByActionName callable = (CallableByActionName) instance;

        // Connect first
        ActionResult connectResult = callable.callByActionName("connect", "/test/path");
        assertTrue(connectResult.isSuccess(), "Connect should succeed");
        assertTrue(connectResult.getResult().contains("/test/path"),
            "Connect result should contain the path");

        // Now list items should succeed
        ActionResult listResult = callable.callByActionName("list-items", "");
        assertTrue(listResult.isSuccess(), "list-items should succeed after connecting");
        assertTrue(listResult.getResult().contains("item1"),
            "list-items should return items");

        // Disconnect
        ActionResult disconnectResult = callable.callByActionName("disconnect", "");
        assertTrue(disconnectResult.isSuccess(), "Disconnect should succeed");
    }

    @Test
    @DisplayName("Should inject ActorSystem via ActorSystemAware")
    public void testActorSystemInjection() throws Exception {
        Class<?> clazz = Class.forName(PLUGIN_CLASS);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        assertTrue(instance instanceof ActorSystemAware,
            "Plugin should implement ActorSystemAware");

        // Create ActorSystem and inject it
        IIActorSystem system = new IIActorSystem("test-system");
        ((ActorSystemAware) instance).setActorSystem(system);

        // Verify injection worked
        CallableByActionName callable = (CallableByActionName) instance;
        ActionResult result = callable.callByActionName("get-system-info", "");

        assertTrue(result.isSuccess(), "get-system-info should succeed after injection");
        assertTrue(result.getResult().contains("ActorSystem injected"),
            "Result should indicate ActorSystem was injected");

        system.terminate();
    }

    @Test
    @DisplayName("Should return error for unknown action")
    public void testUnknownAction() throws Exception {
        Class<?> clazz = Class.forName(PLUGIN_CLASS);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        CallableByActionName callable = (CallableByActionName) instance;
        ActionResult result = callable.callByActionName("unknown-action", "");

        assertFalse(result.isSuccess(), "Unknown action should fail");
        assertTrue(result.getResult().contains("Unknown action"),
            "Error message should contain 'Unknown action'");
    }

    @Test
    @DisplayName("Should fail to disconnect when not connected")
    public void testDisconnectWithoutConnect() throws Exception {
        Class<?> clazz = Class.forName(PLUGIN_CLASS);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        CallableByActionName callable = (CallableByActionName) instance;
        ActionResult result = callable.callByActionName("disconnect", "");

        assertFalse(result.isSuccess(), "Disconnect should fail when not connected");
        assertTrue(result.getResult().contains("Not connected"),
            "Error message should contain 'Not connected'");
    }
}
