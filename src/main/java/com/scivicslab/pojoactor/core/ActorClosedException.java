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

/**
 * Thrown, as the cause of a failed future, for a message that will never run because its actor
 * or its actor system is closing or closed.
 *
 * <p>A message still queued when {@link ActorRef#close()} or {@link ActorSystem#terminate()}
 * discards it completes exceptionally with this exception, and so does a {@code tell} or
 * {@code ask} sent to an actor that is already closed. A message discarded on request, by
 * {@link ActorRef#clearPendingMessages()} or {@link WorkerPool#cancelJobsForActor(String)}, is
 * cancelled instead.</p>
 *
 * @since 4.9.0
 */
public class ActorClosedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String actorName;

    /**
     * Creates the exception for a message of the named actor.
     *
     * @param actorName the name of the actor the message was sent to
     */
    public ActorClosedException(String actorName) {
        super("actor " + actorName + " is closed; the message was not run");
        this.actorName = actorName;
    }

    /**
     * Returns the name of the actor the message was sent to.
     *
     * @return the actor name
     */
    public String getActorName() {
        return actorName;
    }
}
