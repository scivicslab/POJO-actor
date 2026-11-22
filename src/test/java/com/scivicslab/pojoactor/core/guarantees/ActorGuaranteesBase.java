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
package com.scivicslab.pojoactor.core.guarantees;

import static com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.MESSAGES;
import static com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.QUIET_MILLIS;
import static com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.WAIT_SECONDS;
import static com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.await;
import static com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.describe;
import static com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.kind;
import static com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.onCommonPool;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ManagedThreadPool;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Delivery;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Fixture;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.P;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Sent;

/**
 * The guarantees of an actor, each run for every delivery a subclass names. A message to an actor,
 * sent in any way but {@code tellNow}/{@code askNow}, is taken by the actor's receive loop (a
 * virtual thread) in the order sent; a message given a {@code ManagedThreadPool} is handed by that
 * virtual thread to a real thread of the pool and waited for, so that
 * <ol>
 *   <li>a message sent later does not start before an earlier one has ended;</li>
 *   <li>at most one thread is inside the object at any time, whichever deliveries are mixed;</li>
 *   <li>a field with no lock loses no write, whichever deliveries are mixed;</li>
 *   <li>light work runs on the receive loop's virtual thread, and heavy work given a pool on a
 *       real thread of that pool, never on a real thread of Java's common pool.</li>
 * </ol>
 * Every wait is bounded: a step that hangs fails the test with a message, not the build.
 *
 * <p>A subclass names its deliveries and what thread the heavy work is to run on. The class is
 * not itself a test ({@code Base}, not {@code Test}), so surefire runs only its subclasses.</p>
 */
abstract class ActorGuaranteesBase {

    /** The deliveries this subclass is about. */
    abstract List<Delivery> deliveries();

    /** Why the held-up work did not run on the thread the design gives it; null when it did. */
    abstract String heavyThreadProblem(Fixture f, Thread loop, Thread heavy);

    /** Why the pool is not the kind the design uses; null when it is. */
    String poolProblem(Fixture f) {
        return null;
    }

    // ------------------------------------------------------------------ 1. order

    @Test
    @DisplayName("1 order: a message sent after a held-up one does not start until the held-up one has ended")
    void aLaterMessageWaitsForTheEarlierOne() {
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries()) {
            try (Fixture f = new Fixture()) {
                Sent heavy = d.deliver(f, P::heavy);
                await(f.p.heavyStarted, "the held-up message to start");
                CompletableFuture<Void> light = f.actor.tell(P::light);
                Thread.sleep(QUIET_MILLIS);
                long lightStartedWhileHeld = f.p.lightStartStamp;
                f.p.heavyRelease.countDown();
                await(heavy.done(), "the held-up message");
                await(light, "the later message");
                if (lightStartedWhileHeld != 0) {
                    failures.add(d.name() + ": the later message started while the earlier one was still held up");
                } else if (f.p.lightStartStamp <= f.p.heavyEndStamp) {
                    failures.add(d.name() + ": the later message started (stamp " + f.p.lightStartStamp
                            + ") before the earlier one ended (stamp " + f.p.heavyEndStamp + ")");
                }
            } catch (Throwable t) {
                failures.add(describe(d, t));
            }
        }
        assertEquals(List.of(), failures, "deliveries where order was not kept: " + failures.size());
    }

    @Test
    @DisplayName("1 order: 300 messages, alternately this delivery and a plain tell, start in the order sent")
    void messagesStartInTheOrderTheyWereSent() {
        int count = 300;
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries()) {
            try (Fixture f = new Fixture()) {
                List<CompletableFuture<?>> all = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int n = i;
                    if (i % 2 == 0) {
                        Sent sent = d.deliver(f, x -> x.record(n));
                        sent.awaitCall(); // the next message is sent only after this send call has returned
                        all.add(sent.done());
                    } else {
                        all.add(f.actor.tell(x -> x.record(n)));
                    }
                }
                for (CompletableFuture<?> each : all) {
                    await(each, "a message");
                }
                List<Integer> started = new ArrayList<>(f.p.startOrder);
                List<Integer> sent = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    sent.add(i);
                }
                if (!sent.equals(started)) {
                    int at = 0;
                    while (at < started.size() && at < count && started.get(at) == at) {
                        at++;
                    }
                    failures.add(d.name() + ": message " + (at < started.size() ? started.get(at) : -1)
                            + " started at position " + at + ", where message " + at + " was due");
                }
            } catch (Throwable t) {
                failures.add(describe(d, t));
            }
        }
        assertEquals(List.of(), failures, "deliveries where the start order differs from the send order: " + failures.size());
    }

    // ------------------------------------------------------------------ 2. at most one inside

    @Test
    @DisplayName("2 exclusion: this delivery and plain tells, sent at once, never have two threads inside the object")
    void atMostOneThreadIsInsideTheObject() {
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries()) {
            try (Fixture f = new Fixture()) {
                sendMixed(f, d, P::touch);
                int most = f.p.maxInside.get();
                if (most > 1) {
                    failures.add(d.name() + ": " + most + " threads were inside the object at the same time");
                }
            } catch (Throwable t) {
                failures.add(describe(d, t));
            }
        }
        assertEquals(List.of(), failures, "deliveries where two threads were inside at once: " + failures.size());
    }

    // ------------------------------------------------------------------ 3. no lost write

    @Test
    @DisplayName("3 writes: 10 000 increments of an unguarded field, this delivery mixed with plain tells, lose none")
    void noWriteIsLost() {
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries()) {
            try (Fixture f = new Fixture()) {
                sendMixed(f, d, P::increment);
                int value = f.p.counter;
                if (value != MESSAGES) {
                    failures.add(d.name() + ": the field ends at " + value + " after " + MESSAGES + " increments, "
                            + (MESSAGES - value) + " lost");
                }
            } catch (Throwable t) {
                failures.add(describe(d, t));
            }
        }
        assertEquals(List.of(), failures, "deliveries that lost writes: " + failures.size());
    }

    /** The most sends of one delivery on their way at once in the mixed tests. */
    private static final int IN_FLIGHT = 32;

    /**
     * Sends {@link ActorGuaranteeSupport#MESSAGES} messages, half by {@code d} from this thread and
     * half by plain {@code tell} from a virtual thread at the same time, and waits for all of them.
     */
    private static void sendMixed(Fixture f, Delivery d, java.util.function.Consumer<P> work) throws Exception {
        List<CompletableFuture<?>> plain = new ArrayList<>();
        Thread sender = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < MESSAGES / 2; i++) {
                plain.add(f.actor.tell(work));
            }
        });
        // At most IN_FLIGHT sends of this delivery are on their way at once. A send from a real
        // thread of the pool leaves that real thread waiting; this test is about the guarantees,
        // not about how many such waiting senders one pool takes at once.
        java.util.ArrayDeque<CompletableFuture<?>> inFlight = new java.util.ArrayDeque<>();
        for (int i = 0; i < MESSAGES / 2; i++) {
            inFlight.add(d.deliver(f, work).done());
            if (inFlight.size() >= IN_FLIGHT) {
                await(inFlight.poll(), "a message sent as " + d.name());
            }
        }
        while (!inFlight.isEmpty()) {
            await(inFlight.poll(), "a message sent as " + d.name());
        }
        sender.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        for (CompletableFuture<?> each : plain) {
            await(each, "a plain tell");
        }
    }

    // ------------------------------------------------------------------ 4. thread kinds

    @Test
    @DisplayName("4 threads: light work on the receive loop's virtual thread, heavy work as the design gives it")
    void theWorkRunsOnTheThreadsTheDesignGives() {
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries()) {
            try (Fixture f = new Fixture()) {
                List<String> problems = new ArrayList<>();
                String poolProblem = poolProblem(f);
                if (poolProblem != null) {
                    problems.add(poolProblem);
                }
                Thread loop = f.actor.ask(x -> Thread.currentThread()).get(WAIT_SECONDS, TimeUnit.SECONDS);
                if (!loop.isVirtual()) {
                    problems.add("the receive loop runs on a " + kind(loop) + ", not a virtual thread");
                }
                Sent heavy = d.deliver(f, P::heavy);
                await(f.p.heavyStarted, "the heavy work to start");
                f.p.heavyRelease.countDown();
                await(heavy.done(), "the heavy work");
                await(f.actor.tell(P::light), "the light work");

                if (f.p.lightThread != loop) {
                    problems.add("light work ran on " + kind(f.p.lightThread) + ", not on the receive loop's " + kind(loop));
                }
                String heavyProblem = heavyThreadProblem(f, loop, f.p.heavyThread);
                if (heavyProblem != null) {
                    problems.add(heavyProblem);
                }
                if (onCommonPool(f.p.heavyThread) || onCommonPool(f.p.lightThread)) {
                    problems.add("work ran on a real thread of Java's common pool");
                }
                if (!problems.isEmpty()) {
                    failures.add(d.name() + ": " + String.join("; ", problems));
                }
            } catch (Throwable t) {
                failures.add(describe(d, t));
            }
        }
        assertEquals(List.of(), failures, "deliveries that ran work on the wrong thread: " + failures.size());
    }

    // ------------------------------------------------------------------ helpers for subclasses

    /** For deliveries that give a ManagedThreadPool: the heavy work is on a real thread of that pool. */
    static String heavyOnRealPoolThread(Fixture f, Thread heavy) {
        if (heavy == null) {
            return "the heavy work did not run";
        }
        if (heavy.isVirtual()) {
            return "heavy work ran on a " + kind(heavy) + ", not on a real thread of the pool";
        }
        if (!f.poolThreads.contains(heavy)) {
            return "heavy work ran on a " + kind(heavy) + ", which is not one of the pool's real threads";
        }
        return null;
    }

    static String notManagedPool(Fixture f) {
        return f.pool instanceof ManagedThreadPool
                ? null
                : "the pool is a " + f.pool.getClass().getName() + ", not a ManagedThreadPool";
    }
}
