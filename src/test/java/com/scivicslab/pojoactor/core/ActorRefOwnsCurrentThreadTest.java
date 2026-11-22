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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ActorRef.ownsCurrentThread() is true on the actor's own mailbox virtual thread and nowhere else. */
@DisplayName("ActorRef.ownsCurrentThread")
class ActorRefOwnsCurrentThreadTest {

    private final ActorSystem system = new ActorSystem("owns-current-thread", 1);

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    @DisplayName("true inside a message on its mailbox virtual thread")
    void onItsMailbox() throws Exception {
        ActorRef<Object> actor = system.actorOf("a", new Object());
        boolean[] seen = actor.ask(o -> new boolean[] {actor.ownsCurrentThread(), Thread.currentThread().isVirtual()})
            .get(10, TimeUnit.SECONDS);
        assertTrue(seen[0]);
        assertTrue(seen[1], "the mailbox thread is a virtual thread");
    }

    @Test
    @DisplayName("false on the caller's thread, on a pool real thread and on another actor's mailbox")
    void elsewhere() throws Exception {
        ActorRef<Object> actor = system.actorOf("a", new Object());
        ActorRef<Object> other = system.actorOf("b", new Object());

        assertFalse(actor.ownsCurrentThread());
        assertFalse(actor.ask(o -> actor.ownsCurrentThread(), system.getManagedThreadPool()).get(10, TimeUnit.SECONDS));
        assertFalse(other.ask(o -> actor.ownsCurrentThread()).get(10, TimeUnit.SECONDS));
    }
}
