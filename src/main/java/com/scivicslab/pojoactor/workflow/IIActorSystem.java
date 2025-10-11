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

import java.util.concurrent.ConcurrentHashMap;

import com.scivicslab.pojoactor.ActorSystem;

/**
 * Interpreter-interfaced actor system for managing workflow actors.
 *
 * <p>
 * In order to use {@code ActorRef} from an interpreter,
 * it must have an interface that can call methods by their name strings.
 * The {@code IIActorRef} class exists for this purpose.
 * </p>
 * <p>
 * This {@code IIActorSystem} is a subclass of {@code ActorSystem} with the function of managing {@code IIActorRef}.
 * The {@code IIActorSystem} can manage ordinary {@code ActorRefs} as well as the {@code IIActorRef}s,
 * so the two can be used intermixed in a program.
 * </p>
 *
 * <p>The following methods have been added to manage IIActorRef objects:</p>
 * <ul>
 * <li> {@code addIIActor}</li>
 * <li> {@code getIIActor}</li>
 * <li> {@code hasIIActor}</li>
 * <li> {@code removeIIActor}</li>
 * <li> {@code terminateIIActors}</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 */
public class IIActorSystem extends ActorSystem {

    ConcurrentHashMap<String, IIActorRef<?>> iiActors = new ConcurrentHashMap<>();

    /**
     * Constructs a new IIActorSystem with the specified system name.
     *
     * @param systemName the name of this actor system
     */
    public IIActorSystem(String systemName) {
        super(systemName);
    }

    /**
     * Adds an interpreter-interfaced actor to this system.
     *
     * @param <T> the type of the actor object
     * @param actor the actor reference to add
     * @return the added actor reference
     */
    public <T> IIActorRef<T> addIIActor(IIActorRef<T> actor) {
        String actorName = actor.getName();
        iiActors.put(actorName, actor);
        return actor;
    }

    /**
     * Retrieves an interpreter-interfaced actor by name.
     *
     * @param <T> the type of the actor object
     * @param name the name of the actor to retrieve
     * @return the actor reference, or {@code null} if not found
     */
    @SuppressWarnings("unchecked")
    public <T> IIActorRef<T> getIIActor(String name) {
        return (IIActorRef<T>)iiActors.get(name);
    }

    /**
     * Checks if an interpreter-interfaced actor with the given name exists.
     *
     * @param name the name of the actor to check
     * @return {@code true} if the actor exists, {@code false} otherwise
     */
    public boolean hasIIActor(String name) {
        return this.iiActors.containsKey(name);
    }

    /**
     * Removes an interpreter-interfaced actor from this system.
     *
     * @param name the name of the actor to remove
     */
    public void removeIIActor(String name) {
        this.iiActors.remove(name);
    }

    /**
     * Terminates all interpreter-interfaced actors managed by this system.
     *
     * <p>This method closes all registered IIActorRef instances, releasing
     * their associated resources.</p>
     */
    public void terminateIIActors() {
        iiActors.keySet().stream()
            .forEach((name)->iiActors.get(name).close());
    }

}
