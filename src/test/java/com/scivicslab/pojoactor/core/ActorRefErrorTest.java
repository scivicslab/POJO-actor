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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that an {@link Error} thrown by an action completes the message's future
 * exceptionally instead of leaving it incomplete, so that a caller joining the future does not
 * wait forever.
 *
 * <p>An ordinary {@code Error} such as {@link StackOverflowError} leaves the actor alive for the
 * next message, even though {@code StackOverflowError} is itself a {@code VirtualMachineError}.
 * Any other {@link VirtualMachineError} also completes the future, and is then rethrown so that
 * a broken JVM does not carry on as if nothing happened; on an actor's own message loop that
 * ends the loop.</p>
 */
@Tag("ActorRef_260509_oo01")
@DisplayName("ActorRef — an Error thrown by an action completes the future")
class ActorRefErrorTest {

    /** A VirtualMachineError that only this test throws, so no real JVM failure is simulated. */
    static final class TestVirtualMachineError extends VirtualMachineError {
        TestVirtualMachineError(String message) {
            super(message);
        }
    }

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("errorTestSystem", 2);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    /** Waits for the future and returns the cause it completed with; fails on a timeout. */
    private static Throwable causeOf(CompletableFuture<?> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        return e.getCause();
    }

    @Test
    @DisplayName("tell: a StackOverflowError completes the future, and the actor takes the next message")
    void tell_actionThrowsStackOverflowError_futureCompletesAndActorContinues() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("tellOverflow", new StringBuilder("data"));
        StackOverflowError error = new StackOverflowError("test overflow");

        CompletableFuture<Void> future = actor.tell(sb -> { throw error; });

        assertSame(error, causeOf(future));
        assertEquals(4, actor.ask(StringBuilder::length).get(3, TimeUnit.SECONDS));
        assertTrue(actor.isAlive());
        actor.close();
    }

    @Test
    @DisplayName("ask: a StackOverflowError completes the future, and the actor takes the next message")
    void ask_actionThrowsStackOverflowError_futureCompletesAndActorContinues() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("askOverflow", new StringBuilder("data"));
        StackOverflowError error = new StackOverflowError("test overflow");

        CompletableFuture<Integer> future = actor.ask(sb -> { throw error; });

        assertSame(error, causeOf(future));
        assertEquals(4, actor.ask(StringBuilder::length).get(3, TimeUnit.SECONDS));
        assertTrue(actor.isAlive());
        actor.close();
    }

    @Test
    @DisplayName("tell and ask on the managed pool: a StackOverflowError completes the future, and the pool runs the next task")
    void tellAndAskOnManagedPool_actionThrowsStackOverflowError_futureCompletesAndPoolContinues() throws Exception {
        ActorRef<StringBuilder> actor = system.actorOf("poolOverflow", new StringBuilder("data"));
        ExecutorService pool = system.getManagedThreadPool();
        StackOverflowError error = new StackOverflowError("test overflow");

        CompletableFuture<Void> told = actor.tell(sb -> { throw error; }, pool);
        CompletableFuture<Integer> asked = actor.ask(sb -> { throw error; }, pool);

        assertSame(error, causeOf(told));
        assertSame(error, causeOf(asked));
        assertEquals(4, actor.ask(StringBuilder::length, pool).get(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("tell: a VirtualMachineError completes the future and is rethrown, which ends the message loop")
    void tell_actionThrowsVirtualMachineError_futureCompletesAndErrorIsRethrown() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("tellVmError", new StringBuilder("data"));
        TestVirtualMachineError error = new TestVirtualMachineError("test VM error");

        CompletableFuture<Void> future = actor.tell(sb -> { throw error; });

        assertSame(error, causeOf(future));
        // The rethrown error leaves the message loop, so the actor's thread ends.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (actor.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(actor.isAlive(), "the message loop should end after a VirtualMachineError");
        actor.close();
    }

    @Test
    @DisplayName("ask: a VirtualMachineError completes the future and is rethrown, which ends the message loop")
    void ask_actionThrowsVirtualMachineError_futureCompletesAndErrorIsRethrown() throws Exception {
        ActorRef<StringBuilder> actor = new ActorRef<>("askVmError", new StringBuilder("data"));
        TestVirtualMachineError error = new TestVirtualMachineError("test VM error");

        CompletableFuture<Integer> future = actor.ask(sb -> { throw error; });

        assertSame(error, causeOf(future));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (actor.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(actor.isAlive(), "the message loop should end after a VirtualMachineError");
        actor.close();
    }
}
