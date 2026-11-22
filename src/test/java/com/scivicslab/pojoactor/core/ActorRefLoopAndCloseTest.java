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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies two things about an actor's life: its message loop survives an action that leaves the
 * thread's interrupt flag set, and {@link ActorRef#close()} removes from the actor system exactly
 * the registrations that point to the closed actor, whatever name they are under.
 */
@Tag("ActorRef_260509_oo01")
@DisplayName("ActorRef — message loop after an interrupt, and close() by registration")
class ActorRefLoopAndCloseTest {

    /** A VirtualMachineError that only this test throws, so no real JVM failure is simulated. */
    static final class TestVirtualMachineError extends VirtualMachineError {
        TestVirtualMachineError(String message) {
            super(message);
        }
    }

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("loopAndCloseTestSystem", 2);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    @DisplayName("tell: an action that sets the interrupt flag does not stop the actor")
    void tell_actionSetsInterruptFlag_nextMessageStillRuns() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("interruptTell", new StringBuilder("data"));

        actor.tell(sb -> Thread.currentThread().interrupt()).get(3, TimeUnit.SECONDS);

        assertEquals(4, actor.ask(StringBuilder::length).get(3, TimeUnit.SECONDS));
        assertTrue(actor.isAlive());
        actor.close();
    }

    @Test
    @DisplayName("ask: an action that sets the interrupt flag does not stop the actor")
    void ask_actionSetsInterruptFlag_nextMessageStillRuns() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("interruptAsk", new StringBuilder("data"));

        CompletableFuture<Integer> first = actor.ask(sb -> {
            Thread.currentThread().interrupt();
            return sb.length();
        });

        assertEquals(4, first.get(3, TimeUnit.SECONDS));
        assertEquals(4, actor.ask(StringBuilder::length).get(3, TimeUnit.SECONDS));
        assertTrue(actor.isAlive());
        actor.close();
    }

    @Test
    @DisplayName("a message already queued behind the interrupting action still runs")
    void tell_messageQueuedBehindInterruptingAction_runs() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("interruptQueued", new StringBuilder("data"));
        CountDownLatch release = new CountDownLatch(1);

        actor.tell(sb -> {
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Thread.currentThread().interrupt();
        });
        CompletableFuture<Integer> queued = actor.ask(StringBuilder::length);
        release.countDown();

        assertEquals(4, queued.get(3, TimeUnit.SECONDS));
        actor.close();
    }

    @Test
    @DisplayName("when the loop ends on a fatal error, the messages still queued complete exceptionally")
    void loopEndsOnFatalError_queuedMessagesCompleteExceptionally() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("fatalWithQueue", new StringBuilder("data"));
        CountDownLatch release = new CountDownLatch(1);
        TestVirtualMachineError error = new TestVirtualMachineError("test VM error");

        CompletableFuture<Void> failing = actor.tell(sb -> {
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw error;
        });
        CompletableFuture<Integer> queued = actor.ask(StringBuilder::length);
        release.countDown();

        ExecutionException first = assertThrows(ExecutionException.class, () -> failing.get(3, TimeUnit.SECONDS));
        assertSame(error, first.getCause());
        ExecutionException second = assertThrows(ExecutionException.class, () -> queued.get(3, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, second.getCause());
        assertSame(error, second.getCause().getCause());
        actor.close();
    }

    @Test
    @DisplayName("close(): an actor added under an alias is removed from the system")
    void close_actorAddedUnderAlias_aliasIsRemoved() {
        ActorRef<StringBuilder> actor = new ActorRef<>("worker", new StringBuilder("a"));
        system.addActor(actor, "alias");
        assertTrue(system.hasActor("alias"));

        actor.close();

        assertFalse(system.hasActor("alias"), "the alias should be removed when its actor closes");
    }

    @Test
    @DisplayName("close(): another actor registered under the closed actor's own name is left alone")
    void close_otherActorHoldsSameName_otherActorStays() {
        ActorRef<StringBuilder> other = system.actorOf("shared", new StringBuilder("b"));
        ActorRef<StringBuilder> actor = new ActorRef<>("shared", new StringBuilder("a"), system);
        system.addActor(actor, "aliasA");

        actor.close();

        assertSame(other, system.getActor("shared"), "the other actor's registration should stay");
        assertTrue(other.isAlive(), "the other actor should not be closed");
        assertFalse(system.hasActor("aliasA"), "the closed actor's alias should be removed");
    }
}
