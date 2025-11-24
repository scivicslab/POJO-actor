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

import com.scivicslab.pojoactor.ActionResult;
import com.scivicslab.pojoactor.ActorProvider;
import com.scivicslab.pojoactor.CallableByActionName;
import com.scivicslab.pojoactor.DynamicActorLoader;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Generic actor that dynamically loads and creates other actors from plugins.
 *
 * <p>This actor enables workflows to load actors from external JAR files or
 * ServiceLoader providers at runtime, without restarting the application.</p>
 *
 * <p><strong>Supported Actions:</strong></p>
 * <ul>
 *   <li>loadFromJar: Load actor from external JAR file</li>
 *   <li>createFromProvider: Create actor from ServiceLoader provider</li>
 *   <li>listProviders: List all available ActorProvider instances</li>
 *   <li>loadProvidersFromJar: Load ActorProvider plugins from JAR</li>
 * </ul>
 *
 * <p><strong>Example Workflow (Loading from JAR):</strong></p>
 * <pre>{@code
 * <workflow name="dynamic-loading">
 *   <matrix>
 *     <transition from="init" to="loaded">
 *       <action actor="loader" method="loadFromJar">
 *         /plugins/my-actor.jar,com.example.MyActor,myactor
 *       </action>
 *     </transition>
 *
 *     <transition from="loaded" to="done">
 *       <action actor="myactor" method="someAction">args</action>
 *     </transition>
 *   </matrix>
 * </workflow>
 * }</pre>
 *
 * <p><strong>Example Workflow (Loading from ServiceLoader):</strong></p>
 * <pre>{@code
 * <workflow name="service-loader">
 *   <matrix>
 *     <transition from="init" to="loaded">
 *       <action actor="loader" method="createFromProvider">
 *         math,mathactor
 *       </action>
 *     </transition>
 *
 *     <transition from="loaded" to="done">
 *       <action actor="mathactor" method="add">5,3</action>
 *     </transition>
 *   </matrix>
 * </workflow>
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.6.0
 */
public class DynamicActorLoaderActor implements CallableByActionName {

    protected final IIActorSystem system;
    private final List<URLClassLoader> loadedClassLoaders = new ArrayList<>();

    /**
     * Constructs a new DynamicActorLoaderActor.
     *
     * @param system the actor system to register newly loaded actors
     */
    public DynamicActorLoaderActor(IIActorSystem system) {
        this.system = system;
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        try {
            switch (actionName) {
                case "loadFromJar":
                    return loadFromJar(args);

                case "createFromProvider":
                    return createFromProvider(args);

                case "listProviders":
                    return listProviders();

                case "loadProvidersFromJar":
                    return loadProvidersFromJar(args);

                default:
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (Exception e) {
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Loads an actor from an external JAR file.
     *
     * <p>Arguments format: "jarPath,className,actorName"</p>
     * <p>Example: "/plugins/my-actor.jar,com.example.MyActor,myactor"</p>
     *
     * @param args comma-separated: jarPath,className,actorName
     * @return ActionResult indicating success or failure
     */
    private ActionResult loadFromJar(String args) {
        try {
            String[] parts = args.split(",", 3);
            if (parts.length < 3) {
                return new ActionResult(false,
                    "Invalid args. Expected: jarPath,className,actorName");
            }

            String jarPath = parts[0].trim();
            String className = parts[1].trim();
            String actorName = parts[2].trim();

            // Load actor using DynamicActorLoader
            Path jar = Paths.get(jarPath);
            Object actor = DynamicActorLoader.loadActor(jar, className, actorName);

            // Wrap as IIActorRef if it implements CallableByActionName
            if (actor instanceof CallableByActionName) {
                GenericIIAR<?> actorRef = new GenericIIAR<>(actorName, actor, system);
                system.addIIActor(actorRef);

                return new ActionResult(true,
                    "Loaded and registered actor: " + actorName +
                    " from JAR: " + jarPath);
            } else {
                return new ActionResult(false,
                    "Actor must implement CallableByActionName for workflow use");
            }

        } catch (Exception e) {
            return new ActionResult(false, "Failed to load from JAR: " + e.getMessage());
        }
    }

    /**
     * Registers actors from a ServiceLoader ActorProvider.
     *
     * <p>Arguments format: "providerName"</p>
     * <p>Example: "math"</p>
     *
     * <p>This calls the provider's registerActors() method to register
     * all actors provided by that plugin.</p>
     *
     * @param args the provider plugin name
     * @return ActionResult indicating success or failure
     */
    private ActionResult createFromProvider(String args) {
        try {
            String providerName = args.trim();

            // Find provider using ServiceLoader
            ServiceLoader<ActorProvider> loader = ServiceLoader.load(ActorProvider.class);
            ActorProvider targetProvider = null;

            for (ActorProvider provider : loader) {
                if (provider.getPluginName().equals(providerName)) {
                    targetProvider = provider;
                    break;
                }
            }

            if (targetProvider == null) {
                return new ActionResult(false,
                    "Provider not found: " + providerName);
            }

            // Let provider register its actors
            targetProvider.registerActors(system);

            return new ActionResult(true,
                "Registered actors from provider: " + providerName);

        } catch (Exception e) {
            return new ActionResult(false, "Failed to create from provider: " + e.getMessage());
        }
    }

    /**
     * Lists all available ActorProvider instances.
     *
     * @return ActionResult with provider list
     */
    private ActionResult listProviders() {
        try {
            ServiceLoader<ActorProvider> loader = ServiceLoader.load(ActorProvider.class);
            List<String> providerNames = new ArrayList<>();

            for (ActorProvider provider : loader) {
                providerNames.add(provider.getPluginName());
            }

            if (providerNames.isEmpty()) {
                return new ActionResult(true, "No providers found");
            }

            return new ActionResult(true,
                "Available providers: " + String.join(", ", providerNames));

        } catch (Exception e) {
            return new ActionResult(false, "Failed to list providers: " + e.getMessage());
        }
    }

    /**
     * Loads ActorProvider plugins from an external JAR file.
     *
     * <p>The JAR must contain provider implementations registered in
     * META-INF/services/com.scivicslab.pojoactor.ActorProvider</p>
     *
     * @param jarPath path to the JAR file
     * @return ActionResult with loaded provider names
     */
    private ActionResult loadProvidersFromJar(String jarPath) {
        try {
            Path path = Paths.get(jarPath.trim());
            URL jarUrl = path.toUri().toURL();

            // Create new ClassLoader for the plugin JAR
            URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarUrl},
                getClass().getClassLoader()
            );

            loadedClassLoaders.add(classLoader);

            // Load providers from the JAR using ServiceLoader
            ServiceLoader<ActorProvider> loader =
                ServiceLoader.load(ActorProvider.class, classLoader);

            List<String> loadedProviders = new ArrayList<>();

            for (ActorProvider provider : loader) {
                loadedProviders.add(provider.getPluginName());
            }

            if (loadedProviders.isEmpty()) {
                return new ActionResult(false,
                    "No ActorProvider implementations found in JAR: " + jarPath);
            }

            String result = "Loaded " + loadedProviders.size() + " provider(s): " +
                          String.join(", ", loadedProviders);
            return new ActionResult(true, result);

        } catch (Exception e) {
            return new ActionResult(false, "Failed to load JAR: " + e.getMessage());
        }
    }

    /**
     * Generic IIActorRef wrapper for dynamically loaded actors.
     *
     * @param <T> the actor type
     */
    private static class GenericIIAR<T> extends IIActorRef<T> {

        public GenericIIAR(String actorName, T object, IIActorSystem system) {
            super(actorName, object, system);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            if (object instanceof CallableByActionName) {
                return ((CallableByActionName) object).callByActionName(actionName, args);
            }
            return new ActionResult(false, "Actor does not implement CallableByActionName");
        }
    }
}
