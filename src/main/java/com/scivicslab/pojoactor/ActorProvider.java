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

package com.scivicslab.pojoactor;

/**
 * Service provider interface for plugin-based actor registration.
 *
 * Plugins can implement this interface and register themselves using
 * Java's ServiceLoader mechanism. This enables automatic actor discovery
 * and registration at runtime.
 *
 * <h2>Usage in Plugin</h2>
 * <pre>{@code
 * // 1. Implement ActorProvider
 * public class MathPluginProvider implements ActorProvider {
 *     @Override
 *     public void registerActors(ActorSystem system) {
 *         system.actorOf("mathActor", new MathPlugin());
 *         system.actorOf("calculatorActor", new Calculator());
 *     }
 * }
 *
 * // 2. Create META-INF/services/com.scivicslab.pojoactor.ActorProvider
 * // with content:
 * // com.example.plugin.MathPluginProvider
 * }</pre>
 *
 * <h2>Usage in Application</h2>
 * <pre>{@code
 * ActorSystem system = new ActorSystem("mySystem");
 *
 * // Load all registered ActorProviders
 * ServiceLoader<ActorProvider> loader = ServiceLoader.load(ActorProvider.class);
 * for (ActorProvider provider : loader) {
 *     provider.registerActors(system);
 * }
 *
 * // Now all plugin actors are registered and ready to use
 * system.getActor("mathActor").tell(m -> m.calculate());
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.0.0
 * @since 2.0.0
 * @see java.util.ServiceLoader
 */
public interface ActorProvider {

    /**
     * Registers actors provided by this plugin into the given ActorSystem.
     *
     * Implementations should create actor instances and register them
     * using {@link ActorSystem#actorOf(String, Object)} or similar methods.
     *
     * @param system the ActorSystem to register actors into
     */
    void registerActors(ActorSystem system);

    /**
     * Returns the name of this plugin.
     *
     * This is optional but recommended for logging and debugging purposes.
     *
     * @return the plugin name, or a default name if not overridden
     */
    default String getPluginName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Returns the version of this plugin.
     *
     * This is optional but recommended for version management.
     *
     * @return the plugin version, or "unknown" if not overridden
     */
    default String getPluginVersion() {
        return "unknown";
    }
}
