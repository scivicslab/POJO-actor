/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scivicslab.pojoactor.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies state S2.05: DynamicActorLoader loads TestCalculator actors from external JARs.
 *
 * The plugin JAR is compiled at test time from source strings using javax.tools.JavaCompiler.
 * The plugin class (TestMathPlugin) is in package com.example.testplugin — a package not on
 * the host classpath — so URLClassLoader actually reads it from the JAR.
 */
@Tag("S_svc.05")
@DisplayName("DynamicActorLoader — URLClassLoader-based plugin loading (S2.05)")
public class DynamicActorLoaderTest {

    private static Path pluginJar;

    @BeforeAll
    static void buildPluginJar() throws Exception {
        pluginJar = TestPluginJarBuilder.buildMathPluginJar();
    }

    // -------------------------------------------------------------------------
    // Tests — loadActor (standalone, no ActorSystem)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("loadActor — standalone ActorRef")
    class LoadActor {

        @Test
        @DisplayName("returns non-null ActorRef")
        void returnsNonNullActorRef() throws Exception {
            ActorRef<?> actor = DynamicActorLoader.loadActor(
                pluginJar, TestPluginJarBuilder.MATH_PLUGIN_CLASS, "math");
            assertNotNull(actor);
            actor.close();
        }

        @Test
        @DisplayName("loaded actor implements the plugin interface and answers")
        void loadedActorDispatches() throws Exception {
            ActorRef<?> actor = DynamicActorLoader.loadActor(
                pluginJar, TestPluginJarBuilder.MATH_PLUGIN_CLASS, "math");

            String r = actor.ask(a -> ((TestCalculator) a).add("5,3"))
                                  .get(3, TimeUnit.SECONDS);

                        assertEquals("8", r);
            actor.close();
        }

        @Test
        @DisplayName("non-existent JAR path throws exception")
        void nonExistentJarThrows() {
            assertThrows(Exception.class, () ->
                DynamicActorLoader.loadActor(
                    Path.of("/tmp/nonexistent-plugin.jar"),
                    TestPluginJarBuilder.MATH_PLUGIN_CLASS, "math"));
        }

        @Test
        @DisplayName("wrong class name throws ClassNotFoundException")
        void wrongClassNameThrows() {
            assertThrows(Exception.class, () ->
                DynamicActorLoader.loadActor(pluginJar, "com.example.testplugin.NoSuchPlugin", "math"));
        }
    }

    // -------------------------------------------------------------------------
    // Tests — loadActorIntoSystem (registers with ActorSystem)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("loadActorIntoSystem — with ActorSystem registration")
    class LoadActorIntoSystem {

        private ActorSystem system;

        @BeforeEach
        void setUp() { system = new ActorSystem("test"); }

        @AfterEach
        void tearDown() { system.terminate(); }

        @Test
        @DisplayName("registers actor with ActorSystem under the given name")
        void registersActorWithSystem() throws Exception {
            DynamicActorLoader.loadActorIntoSystem(
                system, pluginJar, TestPluginJarBuilder.MATH_PLUGIN_CLASS, "math");

            assertTrue(system.hasActor("math"));
        }

        @Test
        @DisplayName("loaded actor answers through the system")
        void loadedActorDispatchesViaSystem() throws Exception {
            ActorRef<?> actor = DynamicActorLoader.loadActorIntoSystem(
                system, pluginJar, TestPluginJarBuilder.MATH_PLUGIN_CLASS, "math");

            String r = actor.ask(a -> ((TestCalculator) a).add("5,3"))
                                  .get(3, TimeUnit.SECONDS);

                        assertEquals("8", r);
        }

        @Test
        @DisplayName("actor retrieved from system by name dispatches correctly")
        void actorRetrievableByName() throws Exception {
            DynamicActorLoader.loadActorIntoSystem(
                system, pluginJar, TestPluginJarBuilder.MATH_PLUGIN_CLASS, "math");

            ActorRef<?> retrieved = system.getActor("math");
            assertNotNull(retrieved);

            String r = retrieved.ask(a -> ((TestCalculator) a).add("3,4"))
                                      .get(3, TimeUnit.SECONDS);
                        assertEquals("7", r);
        }

        @Test
        @DisplayName("actor state persists across sequential tell() and ask() calls")
        void statePersistsAcrossCalls() throws Exception {
            ActorRef<?> actor = DynamicActorLoader.loadActorIntoSystem(
                system, pluginJar, TestPluginJarBuilder.MATH_PLUGIN_CLASS, "math");

            actor.tell(a -> ((TestCalculator) a).add("10,5"));

            String r = actor.ask(a -> ((TestCalculator) a).lastResult())
                                  .get(3, TimeUnit.SECONDS);

            assertEquals("15", r);
        }
    }
}
