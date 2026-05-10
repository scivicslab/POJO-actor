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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reflection-based dispatch helper for {@link Action @Action}-annotated methods.
 *
 * <p>Lazily scans a target object's class for methods annotated with {@link Action},
 * validates their signatures, and caches the discovered methods for fast subsequent
 * invocations.  Designed to be used as a delegate field inside classes that cannot
 * extend {@link AbstractCallableByActionName} due to Java's single-inheritance
 * constraint (e.g. classes that already extend a framework base class).</p>
 *
 * <p><strong>JVM only.</strong> Uses reflection and is not compatible with GraalVM
 * Native Image without additional {@code reflect-config.json} configuration.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyFrameworkActor extends FrameworkBase implements CallableByActionName {
 *     private final ActionDispatcher dispatcher = new ActionDispatcher(this);
 *
 *     @Action("doWork")
 *     public ActionResult doWork(String args) { ... }
 *
 *     @Override
 *     public ActionResult callByActionName(String actionName, String args) {
 *         ActionResult r = dispatcher.invoke(actionName, args);
 *         if (r != null) return r;
 *         // handle additional cases or return unknown-action failure
 *         return new ActionResult(false, "Unknown action: " + actionName);
 *     }
 * }
 * }</pre>
 *
 * @since 2.0.0
 * @see Action
 * @see AbstractCallableByActionName
 */
public class ActionDispatcher {

    private static final Logger logger = Logger.getLogger(ActionDispatcher.class.getName());

    private final Object target;
    private Map<String, Method> actionMethods = null;

    /**
     * Creates a dispatcher that will scan and invoke {@link Action @Action}-annotated
     * methods on {@code target}.
     *
     * @param target the object to scan; typically {@code this} from the owner class
     */
    public ActionDispatcher(Object target) {
        this.target = target;
    }

    private void discover() {
        if (actionMethods != null) {
            return;
        }

        actionMethods = new HashMap<>();

        for (Method method : target.getClass().getMethods()) {
            Action action = method.getAnnotation(Action.class);
            if (action == null) {
                continue;
            }

            if (method.getReturnType() != ActionResult.class) {
                logger.warning(String.format(
                    "@Action method %s.%s has invalid return type %s (expected ActionResult)",
                    target.getClass().getSimpleName(), method.getName(),
                    method.getReturnType().getSimpleName()));
                continue;
            }

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != String.class) {
                logger.warning(String.format(
                    "@Action method %s.%s must accept exactly one String parameter",
                    target.getClass().getSimpleName(), method.getName()));
                continue;
            }

            String actionName = action.value();
            if (actionMethods.containsKey(actionName)) {
                logger.warning(String.format(
                    "Duplicate @Action(\"%s\") on %s.%s — skipped",
                    actionName, target.getClass().getSimpleName(), method.getName()));
                continue;
            }

            method.setAccessible(true);
            actionMethods.put(actionName, method);
        }
    }

    /**
     * Invokes the {@link Action @Action}-annotated method whose name matches {@code actionName}.
     *
     * @param actionName the action name to look up
     * @param args the argument string to pass to the method
     * @return the {@link ActionResult} returned by the method, or {@code null} if no
     *         matching method was found (so the caller can fall through to other dispatch stages)
     */
    public ActionResult invoke(String actionName, String args) {
        discover();

        Method method = actionMethods.get(actionName);
        if (method == null) {
            return null;
        }

        try {
            return (ActionResult) method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            logger.log(Level.WARNING, "Error invoking @Action " + actionName, e);
            return new ActionResult(false, "Error in " + actionName + ": " + message);
        } catch (IllegalAccessException e) {
            logger.log(Level.SEVERE, "Cannot access @Action method " + actionName, e);
            return new ActionResult(false, "Cannot access " + actionName + ": " + e.getMessage());
        }
    }

    /**
     * Returns {@code true} if an {@link Action @Action}-annotated method is registered
     * for the given name.
     */
    public boolean has(String actionName) {
        discover();
        return actionMethods.containsKey(actionName);
    }
}
