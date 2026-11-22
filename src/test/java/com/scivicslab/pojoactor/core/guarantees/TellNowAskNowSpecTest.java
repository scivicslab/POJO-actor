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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Delivery;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Fixture;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.P;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Sent;

/**
 * {@code tellNow} and {@code askNow} do not go through the actor's receive loop: by specification
 * they run beside the mailbox, each on a virtual thread of its own. So the guarantees of the other
 * deliveries are not asked of them; what is asked is the specified behaviour, the opposite of
 * those guarantees. A change that sent them through the mailbox would turn these tests red.
 */
@Tag("PD_230128_oo01")
@DisplayName("Actor guarantees — tellNow and askNow run beside the mailbox, by specification")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TellNowAskNowSpecTest {

    private final List<Delivery> deliveries = List.of(ActorGuaranteeSupport.TELL_NOW, ActorGuaranteeSupport.ASK_NOW);

    @Test
    @DisplayName("1 order: a mailbox message is not held back by a held-up now message, and a now message not by a held-up mailbox message")
    void nowMessagesAndMailboxMessagesDoNotWaitForEachOther() {
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries) {
            // a held-up now message; a mailbox message sent after it must run at once
            try (Fixture f = new Fixture()) {
                Sent heavy = d.deliver(f, P::heavy);
                await(f.p.heavyStarted, "the held-up now message to start");
                await(f.actor.tell(P::light), "a mailbox message while a now message is held up");
                long endedWhileHeld = f.p.heavyEndStamp;
                f.p.heavyRelease.countDown();
                await(heavy.done(), "the held-up now message");
                if (endedWhileHeld != 0) {
                    failures.add(d.name() + ": the now message ended before it was released");
                }
            } catch (Throwable t) {
                failures.add(describe(d, t) + " (mailbox message behind a now message)");
            }
            // a held-up mailbox message; a now message sent after it must run at once
            try (Fixture f = new Fixture()) {
                CompletableFuture<Void> heavy = f.actor.tell(P::heavy);
                await(f.p.heavyStarted, "the held-up mailbox message to start");
                await(d.deliver(f, P::light).done(), "a now message while a mailbox message is held up");
                long endedWhileHeld = f.p.heavyEndStamp;
                f.p.heavyRelease.countDown();
                await(heavy, "the held-up mailbox message");
                if (endedWhileHeld != 0) {
                    failures.add(d.name() + ": the mailbox message ended before it was released");
                }
            } catch (Throwable t) {
                failures.add(describe(d, t) + " (now message behind a mailbox message)");
            }
        }
        assertEquals(List.of(), failures, "now messages that waited for the mailbox: " + failures.size());
    }

    @Test
    @DisplayName("2 concurrency: a now message runs inside the object while a mailbox message is inside it")
    void aNowMessageRunsAtTheSameTimeAsAMailboxMessage() {
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries) {
            try (Fixture f = new Fixture()) {
                CompletableFuture<Void> heavy = f.actor.tell(P::heavy);
                await(f.p.heavyStarted, "the held-up mailbox message to start");
                await(d.deliver(f, P::touch).done(), "a now message while a mailbox message is inside the object");
                int most = f.p.maxInside.get();
                f.p.heavyRelease.countDown();
                await(heavy, "the held-up mailbox message");
                if (most != 2) {
                    failures.add(d.name() + ": at most " + most + " thread(s) were inside at once, 2 were due");
                }
            } catch (Throwable t) {
                failures.add(describe(d, t));
            }
        }
        assertEquals(List.of(), failures, "now messages that did not run beside the mailbox: " + failures.size());
    }

    @Test
    @DisplayName("3 completion: 10 000 now messages each run exactly once (no exclusion is promised, so no lost write is checked)")
    void everyNowMessageRunsOnce() {
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries) {
            try (Fixture f = new Fixture()) {
                List<CompletableFuture<?>> all = new ArrayList<>();
                for (int i = 0; i < MESSAGES; i++) {
                    all.add(d.deliver(f, P::count).done());
                }
                for (CompletableFuture<?> each : all) {
                    await(each, "a now message");
                }
                int runs = f.p.runs.get();
                if (runs != MESSAGES) {
                    failures.add(d.name() + ": " + runs + " runs after " + MESSAGES + " messages");
                }
            } catch (Throwable t) {
                failures.add(describe(d, t));
            }
        }
        assertEquals(List.of(), failures, "now deliveries with a wrong number of runs: " + failures.size());
    }

    @Test
    @DisplayName("4 threads: the work runs on a virtual thread that is not the receive loop's, never on a real thread")
    void aNowMessageRunsOnItsOwnVirtualThread() {
        List<String> failures = new ArrayList<>();
        for (Delivery d : deliveries) {
            try (Fixture f = new Fixture()) {
                Thread loop = f.actor.ask(x -> Thread.currentThread()).get(WAIT_SECONDS, TimeUnit.SECONDS);
                await(d.deliver(f, P::light).done(), "a now message");
                Thread ran = f.p.lightThread;
                List<String> problems = new ArrayList<>();
                if (ran == null || !ran.isVirtual()) {
                    problems.add("ran on " + kind(ran) + ", not on a virtual thread");
                }
                if (ran == loop) {
                    problems.add("ran on the receive loop's " + kind(loop) + ", which a now message bypasses");
                }
                if (onCommonPool(ran) || f.poolThreads.contains(ran)) {
                    problems.add("ran on a real thread of the common pool or of the ManagedThreadPool");
                }
                if (!problems.isEmpty()) {
                    failures.add(d.name() + ": " + String.join("; ", problems));
                }
            } catch (Throwable t) {
                failures.add(describe(d, t));
            }
        }
        assertEquals(List.of(), failures, "now deliveries on the wrong thread: " + failures.size());
    }
}
