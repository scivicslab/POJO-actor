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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dynamic actor loader for runtime-extensible actor system.
 *
 * This class enables loading actors from external JAR files at runtime,
 * providing OSGi-like plugin functionality using only JDK standard APIs.
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * // Load an actor from external JAR
 * Path pluginJar = Paths.get("plugins/math-plugin.jar");
 * ActorRef<Object> actor = DynamicActorLoader.loadActor(
 *     pluginJar,
 *     "com.example.plugin.MathPlugin",
 *     "mathActor"
 * );
 *
 * // Use the dynamically loaded actor
 * actor.tell(obj -> {
 *     // Invoke methods via reflection
 *     Method method = obj.getClass().getMethod("add", int.class, int.class);
 *     int result = (int) method.invoke(obj, 2, 3);
 *     System.out.println("Result: " + result);
 * });
 * }</pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Load any POJO from external JAR as an actor</li>
 *   <li>No OSGi or JPMS required - uses standard URLClassLoader</li>
 *   <li>Supports hot-reload by closing classloader and reloading</li>
 *   <li>Compatible with GraalVM Native Image (with configuration)</li>
 * </ul>
 *
 * <h2>Requirements</h2>
 * <ul>
 *   <li>Plugin class must have a public no-argument constructor</li>
 *   <li>JAR file must be accessible via filesystem</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 * @since 2.0.0
 */
public class DynamicActorLoader {

    private static final Logger logger = Logger.getLogger(DynamicActorLoader.class.getName());

    /**
     * Loads a class from an external JAR file and wraps it as an ActorRef.
     *
     * The loaded class must have a public no-argument constructor.
     * The resulting actor can receive messages just like any other actor.
     *
     * @param <T> the type of the actor object
     * @param jarPath path to the JAR file containing the class
     * @param className fully qualified class name to load
     * @param actorName name for the actor
     * @return an ActorRef wrapping an instance of the loaded class
     * @throws Exception if JAR loading, class loading, or instantiation fails
     *
     * @see ActorRef
     */
    @SuppressWarnings("unchecked")
    public static <T> ActorRef<T> loadActor(Path jarPath, String className, String actorName)
            throws Exception {

        logger.log(Level.INFO, "Loading actor from JAR: {0}, class: {1}, name: {2}",
                   new Object[]{jarPath, className, actorName});

        // 1. Create a classloader for the plugin JAR
        URLClassLoader loader = new URLClassLoader(
            new URL[]{jarPath.toUri().toURL()},
            DynamicActorLoader.class.getClassLoader()
        );

        // 2. Load the class (must be a POJO with a public no-arg constructor)
        Class<?> clazz = loader.loadClass(className);
        logger.log(Level.INFO, "Loaded class: {0}", clazz.getName());

        // 3. Instantiate the class
        Object instance = clazz.getDeclaredConstructor().newInstance();
        logger.log(Level.INFO, "Created instance of: {0}", clazz.getName());

        // 4. Wrap the instance as an ActorRef and return
        ActorRef<T> actor = new ActorRef<>(actorName, (T) instance);
        logger.log(Level.INFO, "Created actor: {0}", actorName);

        return actor;
    }

    /**
     * Loads a class from an external JAR file and wraps it as an ActorRef,
     * registering it with the given ActorSystem.
     *
     * This is a convenience method that combines loading and registration.
     *
     * @param <T> the type of the actor object
     * @param system the ActorSystem to register the actor with
     * @param jarPath path to the JAR file containing the class
     * @param className fully qualified class name to load
     * @param actorName name for the actor
     * @return an ActorRef wrapping an instance of the loaded class
     * @throws Exception if JAR loading, class loading, or instantiation fails
     *
     * @see ActorSystem#addActor(ActorRef)
     */
    @SuppressWarnings("unchecked")
    public static <T> ActorRef<T> loadActorIntoSystem(
            ActorSystem system,
            Path jarPath,
            String className,
            String actorName) throws Exception {

        logger.log(Level.INFO, "Loading actor into system: {0}, class: {1}, name: {2}",
                   new Object[]{jarPath, className, actorName});

        // 1. Create a classloader for the plugin JAR
        URLClassLoader loader = new URLClassLoader(
            new URL[]{jarPath.toUri().toURL()},
            DynamicActorLoader.class.getClassLoader()
        );

        // 2. Load the class (must be a POJO with a public no-arg constructor)
        Class<?> clazz = loader.loadClass(className);
        logger.log(Level.INFO, "Loaded class: {0}", clazz.getName());

        // 3. Instantiate the class
        Object instance = clazz.getDeclaredConstructor().newInstance();
        logger.log(Level.INFO, "Created instance of: {0}", clazz.getName());

        // 4. Create ActorRef with ActorSystem
        ActorRef<T> actor = new ActorRef<>(actorName, (T) instance, system);

        // 5. Register with system
        system.addActor(actor);

        logger.log(Level.INFO, "Registered actor {0} with system {1}",
                   new Object[]{actorName, system});

        return actor;
    }
}
