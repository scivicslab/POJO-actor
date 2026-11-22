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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that when two callers race {@link ActorSystem#actorOf(String, Object)} with the same
 * name, the loser's actor -- made, and its mailbox virtual thread started, before registration
 * failed -- is closed, and the loser's caller still gets the {@link IllegalStateException} a
 * duplicate name gets.
 *
 * <p>The race is forced: the registry's {@code containsKey} waits until both callers have checked
 * the name, so both find it free and both go on to make an actor. The test keeps only weak
 * references to the two actors and their POJOs, so that it can tell, after a garbage collection,
 * whether anything still holds the loser.</p>
 */
@Tag("ActorSystem_260509_oo01")
@DisplayName("ActorSystem — the loser of an actorOf race is closed")
class ActorOfRaceTest {

    /** An ActorSystem whose registry lets two callers both see a name as free. */
    static final class RacingSystem extends ActorSystem {
        final CountDownLatch bothChecked = new CountDownLatch(2);
        final List<WeakReference<ActorRef<?>>> offered = new CopyOnWriteArrayList<>();

        RacingSystem() {
            super("race", 1);
            actors = new ConcurrentHashMap<>() {
                @Override
                public boolean containsKey(Object key) {
                    boolean held = super.containsKey(key);
                    bothChecked.countDown();
                    try {
                        bothChecked.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return held;
                }

                @Override
                public ActorRef<?> putIfAbsent(String key, ActorRef<?> value) {
                    offered.add(new WeakReference<>(value));
                    return super.putIfAbsent(key, value);
                }
            };
        }
    }

    private RacingSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    private static Thread mailboxThreadOf(ActorRef<?> actor) throws ReflectiveOperationException {
        Field field = ActorRef.class.getDeclaredField("actorThread");
        field.setAccessible(true);
        return (Thread) field.get(actor);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("two actorOf calls with one name: one actor lives, the loser's mailbox thread ends and the loser is collected")
    void loserIsClosed() throws Exception {
        system = new RacingSystem();
        List<WeakReference<Object>> pojos = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> lost = new AtomicReference<>();
        AtomicReference<String> won = new AtomicReference<>();

        // Two virtual threads, so that both are inside actorOf at once. Neither keeps a strong
        // reference to what it made.
        Runnable attempt = () -> {
            Object pojo = new Object();
            pojos.add(new WeakReference<>(pojo));
            try {
                won.set(system.actorOf("x", pojo).getName());
            } catch (IllegalStateException e) {
                lost.set(e);
            }
        };
        Thread a = Thread.ofVirtual().start(attempt);
        Thread b = Thread.ofVirtual().start(attempt);
        a.join(Duration.ofSeconds(5));
        b.join(Duration.ofSeconds(5));

        assertEquals("x", won.get(), "one caller should have registered x");
        assertTrue(lost.get().getMessage().startsWith("another actor is already registered as x"), lost.get().getMessage());
        assertEquals(2, system.offered.size(), "both callers should have made an actor");

        ActorRef<?> winner = system.getActor("x");
        assertTrue(winner.isAlive());
        assertEquals(1, system.offered.stream().map(WeakReference::get).filter(r -> r != null && r.isAlive()).count(),
                "exactly one actor is live");

        // The loser's mailbox virtual thread has ended. The thread is looked up and dropped
        // inside the helper, so the test holds no strong reference to the loser.
        assertTrue(loserLoopEnded(winner), "the loser's mailbox virtual thread should have ended");

        // Nothing holds the loser any more: after a garbage collection both its ActorRef and its
        // POJO are gone, and only the winner's remain.
        collectUntilCleared(system.offered, winner);
        assertEquals(1, system.offered.stream().filter(r -> r.get() != null).count(), "the loser's ActorRef is still reachable");
        Object winnerPojo = ((ActorRef<Object>) winner).ask(o -> o).get(3, TimeUnit.SECONDS);
        collectUntilCleared(pojos, winnerPojo);
        assertEquals(1, pojos.stream().filter(r -> r.get() != null).count(), "the loser's POJO is still reachable");
    }

    private boolean loserLoopEnded(ActorRef<?> winner) throws Exception {
        ActorRef<?> loser = system.offered.stream().map(WeakReference::get)
                .filter(r -> r != null && r != winner).findFirst().orElse(null);
        if (loser == null) {
            return true; // already collected: its loop cannot be holding it
        }
        Thread loop = mailboxThreadOf(loser);
        loop.join(Duration.ofSeconds(3));
        return !loop.isAlive();
    }

    /**
     * Runs the garbage collector until every reference except the one to {@code keep} is cleared,
     * at most ten times. A loser that is still held never clears, so the assertion that follows
     * fails; one that is free is cleared by the first full collection on the JVMs we use.
     */
    private static <R> void collectUntilCleared(List<WeakReference<R>> refs, Object keep) {
        for (int i = 0; i < 10; i++) {
            if (refs.stream().map(WeakReference::get).allMatch(r -> r == null || r == keep)) {
                return;
            }
            System.gc();
        }
    }
}
