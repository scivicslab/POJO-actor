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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that an action which closes its own actor, or terminates its own actor system, can
 * go on running afterwards on the same thread: the thread's interrupt flag is not left set, and
 * the next blocking call in the action does not throw a spurious {@code InterruptedException}.
 * Checked on the actor's mailbox virtual thread and on a ManagedThreadPool real thread.
 */
@Tag("ActorRef_260509_oo01")
@DisplayName("ActorRef — close() or terminate() called from inside an action leaves the thread usable")
class CloseFromInsideActionTest {

    private ActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    /**
     * Runs {@code shutdown} inside an action, then reports, from the same action, the thread's
     * interrupt flag and whether a blocking wait on an open latch threw InterruptedException.
     */
    private static String afterShutdown(Runnable shutdown) {
        shutdown.run();
        boolean flag = Thread.currentThread().isInterrupted();
        String waited;
        try {
            new CountDownLatch(0).await(1, TimeUnit.SECONDS);
            waited = "no InterruptedException";
        } catch (InterruptedException e) {
            waited = "InterruptedException";
        }
        return "interrupted=" + flag + ", " + waited;
    }

    private static final String CLEAN = "interrupted=false, no InterruptedException";

    private ActorRef<Object> newActor() {
        system = new ActorSystem("close-from-inside", 2);
        return system.actorOf("self", new Object());
    }

    private String onMailbox(ActorRef<Object> actor, Runnable shutdown) throws Exception {
        return actor.ask(o -> afterShutdown(shutdown)).get(3, TimeUnit.SECONDS);
    }

    private String onPool(ActorRef<Object> actor, Runnable shutdown) throws Exception {
        return actor.ask(o -> afterShutdown(shutdown), system.getManagedThreadPool()).get(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("close() from an action on the actor's mailbox virtual thread")
    void closeOnMailbox() throws Exception {
        ActorRef<Object> actor = newActor();
        assertEquals(CLEAN, onMailbox(actor, actor::close));
    }

    @Test
    @DisplayName("terminate() from an action on the actor's mailbox virtual thread")
    void terminateOnMailbox() throws Exception {
        ActorRef<Object> actor = newActor();
        assertEquals(CLEAN, onMailbox(actor, system::terminate));
    }

    @Test
    @DisplayName("close() from an action on a ManagedThreadPool real thread")
    void closeOnPool() throws Exception {
        ActorRef<Object> actor = newActor();
        assertEquals(CLEAN, onPool(actor, actor::close));
    }

    @Test
    @DisplayName("terminate() from an action on a ManagedThreadPool real thread")
    void terminateOnPool() throws Exception {
        ActorRef<Object> actor = newActor();
        assertEquals(CLEAN, onPool(actor, system::terminate));
    }
}
