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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Pins what an actor guarantees about the calls into its object, on every way of sending it work.
 *
 * <p>The actor's receive loop runs on the actor's own virtual thread and takes one message at a
 * time, in the order sent. A message given a {@link ManagedThreadPool} or another
 * {@link ExecutorService} is still taken in that turn: the receive loop hands the action to a
 * real thread of the pool (or a thread of the executor) and waits, on its virtual thread, until
 * the action has finished. So at most one thread is inside the object at any time, and the
 * object sees the messages in the order they were sent, whichever path each took. Only
 * {@code tellNow} and {@code askNow} run outside the turn, and that is their contract.</p>
 *
 * <p>Every wait has a timeout, so a broken guarantee shows as a failure, not a hang.</p>
 */
@Tag("ActorRef_260509_oo01")
@DisplayName("ActorRef — the receive loop: exclusion, order and threads on every path")
class ReceiveLoopGuaranteesTest {

    private static final int POOL_SIZE = 2;
    private static final long WAIT_SECONDS = 10;

    private ActorSystem system;
    private ManagedThreadPool pool;
    private ExecutorService plainExecutor;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("receive-loop", POOL_SIZE);
        pool = (ManagedThreadPool) system.getManagedThreadPool();
        plainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        system.terminate();
        plainExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------
    // The object every test sends to
    // ------------------------------------------------------------------------------------------

    /** Counts the calls inside it at once, and records each call's order and thread. */
    static final class Probe {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<Integer> started = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> finished = Collections.synchronizedList(new ArrayList<>());
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> onPoolThread = Collections.synchronizedList(new ArrayList<>());
        volatile ManagedThreadPool pool;
        int plainCounter;

        /** One call: enter, record, optionally work for a while, leave. */
        int call(int seq, long workMicros) {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            started.add(seq);
            threads.add(Thread.currentThread());
            onPoolThread.add(pool != null && pool.ownsCurrentThread());
            if (workMicros > 0) {
                sleepMicros(workMicros);
            }
            finished.add(seq);
            inFlight.decrementAndGet();
            return seq;
        }
    }

    private static void sleepMicros(long micros) {
        try {
            TimeUnit.MICROSECONDS.sleep(micros);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final AtomicInteger ACTOR_NUMBER = new AtomicInteger();

    /** A probe actor; the name gets a number, as the dynamic tests of one factory share a system. */
    private ActorRef<Probe> newProbe(String name) {
        Probe probe = new Probe();
        probe.pool = pool;
        return system.actorOf(name + "-" + ACTOR_NUMBER.incrementAndGet(), probe);
    }

    private static Probe objectOf(ActorRef<Probe> actor) throws Exception {
        return actor.ask(p -> p).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------------------------------
    // The paths
    // ------------------------------------------------------------------------------------------

    /** Which thread a path's action is expected to run on. */
    enum Runs { ON_MAILBOX_THREAD, ON_POOL_REAL_THREAD, ON_EXECUTOR_THREAD }

    /** A way of sending work to an actor, from one sender, in order. */
    interface Path {
        String label();

        Runs runs();

        /**
         * Sends every action, in order, from this path's sender, and returns their futures. The
         * sender sends them all before any is waited for, except where the path itself is a
         * sender that waits for each (the waiting inline path).
         */
        List<CompletableFuture<?>> sendAll(ReceiveLoopGuaranteesTest t, ActorRef<Probe> actor,
                                           List<Function<Probe, Integer>> actions) throws Exception;
    }

    private static Path path(String label, Runs runs, Sender sender) {
        return new Path() {
            public String label() { return label; }
            public Runs runs() { return runs; }
            public List<CompletableFuture<?>> sendAll(ReceiveLoopGuaranteesTest t, ActorRef<Probe> actor,
                                                      List<Function<Probe, Integer>> actions) throws Exception {
                return sender.sendAll(t, actor, actions);
            }
            public String toString() { return label; }
        };
    }

    @FunctionalInterface
    interface Sender {
        List<CompletableFuture<?>> sendAll(ReceiveLoopGuaranteesTest t, ActorRef<Probe> actor,
                                           List<Function<Probe, Integer>> actions) throws Exception;
    }

    /** Sends each action by one call, from the calling thread. */
    private static Sender each(java.util.function.BiFunction<ReceiveLoopGuaranteesTest, ActorRef<Probe>,
            Function<Function<Probe, Integer>, CompletableFuture<?>>> how) {
        return (t, actor, actions) -> {
            Function<Function<Probe, Integer>, CompletableFuture<?>> send = how.apply(t, actor);
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (Function<Probe, Integer> a : actions) {
                futures.add(send.apply(a));
            }
            return futures;
        };
    }

    static final Path TELL = path("tell", Runs.ON_MAILBOX_THREAD,
            each((t, actor) -> a -> actor.tell(p -> a.apply(p))));
    static final Path ASK = path("ask", Runs.ON_MAILBOX_THREAD,
            each((t, actor) -> actor::ask));
    static final Path TELL_POOL = path("tell(action, ManagedThreadPool)", Runs.ON_POOL_REAL_THREAD,
            each((t, actor) -> a -> actor.tell(p -> a.apply(p), t.pool)));
    static final Path ASK_POOL = path("ask(action, ManagedThreadPool)", Runs.ON_POOL_REAL_THREAD,
            each((t, actor) -> a -> actor.ask(a, t.pool)));
    static final Path TELL_EXECUTOR = path("tell(action, ExecutorService)", Runs.ON_EXECUTOR_THREAD,
            each((t, actor) -> a -> actor.tell(p -> a.apply(p), t.plainExecutor)));
    static final Path ASK_EXECUTOR = path("ask(action, ExecutorService)", Runs.ON_EXECUTOR_THREAD,
            each((t, actor) -> a -> actor.ask(a, t.plainExecutor)));

    /** The inline path: the sender is a raw task on one of the pool's own real threads. */
    static final Path INLINE_FROM_POOL_THREAD = path("ask(action, pool) from a pool real thread",
            Runs.ON_POOL_REAL_THREAD, (t, actor, actions) -> CompletableFuture.supplyAsync(() -> {
                List<CompletableFuture<?>> futures = new ArrayList<>();
                for (Function<Probe, Integer> a : actions) {
                    futures.add(actor.ask(a, t.pool));
                }
                return futures;
            }, t.pool).get(WAIT_SECONDS, TimeUnit.SECONDS));

    /** The inline path where the pool real thread waits for each answer before the next. */
    static final Path INLINE_WAITING_ON_POOL_THREAD = path("ask(action, pool).join() on a pool real thread",
            Runs.ON_POOL_REAL_THREAD, (t, actor, actions) -> {
                List<CompletableFuture<?>> futures = new ArrayList<>();
                for (Function<Probe, Integer> a : actions) {
                    futures.add(CompletableFuture.supplyAsync(() -> actor.ask(a, t.pool).join(), t.pool));
                }
                return futures;
            });

    /** The inline path from another actor: its pool action sends to this actor and waits. */
    static final Path INLINE_FROM_OTHER_ACTOR = path("ask(action, pool) from another actor's pool action",
            Runs.ON_POOL_REAL_THREAD, (t, actor, actions) -> {
                ActorRef<Object> other = t.system.actorOf("other-" + ACTOR_NUMBER.incrementAndGet(), new Object());
                List<CompletableFuture<?>> futures = new ArrayList<>();
                for (Function<Probe, Integer> a : actions) {
                    futures.add(other.ask(o -> actor.ask(a, t.pool).join(), t.pool));
                }
                return futures;
            });

    /** Every path above, taken in turn by one sender. */
    static final Path MIX = path("a mix of all paths", null, (t, actor, actions) -> {
        Path[] rotation = { TELL, ASK_POOL, ASK, TELL_POOL, ASK_EXECUTOR, TELL_EXECUTOR };
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            futures.addAll(rotation[i % rotation.length].sendAll(t, actor, List.of(actions.get(i))));
        }
        return futures;
    });

    static final List<Path> ALL = List.of(TELL, ASK, TELL_POOL, ASK_POOL, TELL_EXECUTOR, ASK_EXECUTOR,
            INLINE_FROM_POOL_THREAD, INLINE_WAITING_ON_POOL_THREAD, INLINE_FROM_OTHER_ACTOR, MIX);

    static final List<Path> OFF_THE_MAILBOX_THREAD = List.of(TELL_POOL, ASK_POOL, TELL_EXECUTOR, ASK_EXECUTOR,
            INLINE_FROM_POOL_THREAD, INLINE_FROM_OTHER_ACTOR);

    private static void awaitAll(List<CompletableFuture<?>> futures) throws Exception {
        for (CompletableFuture<?> f : futures) {
            f.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static List<Function<Probe, Integer>> calls(int from, int count, long workMicros) {
        List<Function<Probe, Integer>> actions = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            int seq = i;
            actions.add(p -> p.call(seq, workMicros));
        }
        return actions;
    }

    private static Stream<DynamicTest> forEach(List<Path> paths, String guarantee, PathTest test) {
        return paths.stream().map(path -> DynamicTest.dynamicTest(guarantee + " — " + path.label(),
                () -> test.run(path)));
    }

    @FunctionalInterface
    interface PathTest {
        void run(Path path) throws Exception;
    }

    // ------------------------------------------------------------------------------------------
    // 1. Exclusion
    // ------------------------------------------------------------------------------------------

    @TestFactory
    @DisplayName("1 exclusion: at most one call is inside the object at any time")
    Stream<DynamicTest> exclusion() {
        return forEach(ALL, "exclusion", path -> {
            ActorRef<Probe> actor = newProbe("exclusion");
            int senders = 4, perSender = 25;
            List<CompletableFuture<List<CompletableFuture<?>>>> sent = new ArrayList<>();
            for (int s = 0; s < senders; s++) {
                int from = s * perSender;
                CompletableFuture<List<CompletableFuture<?>>> f = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        f.complete(path.sendAll(this, actor, calls(from, perSender, 300)));
                    } catch (Throwable e) {
                        f.completeExceptionally(e);
                    }
                });
                sent.add(f);
            }
            for (CompletableFuture<List<CompletableFuture<?>>> f : sent) {
                awaitAll(f.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            Probe probe = objectOf(actor);
            assertEquals(senders * perSender, probe.finished.size());
            assertEquals(1, probe.maxInFlight.get(), "more than one call was inside the object at once");
        });
    }

    // ------------------------------------------------------------------------------------------
    // 2. Order
    // ------------------------------------------------------------------------------------------

    @TestFactory
    @DisplayName("2 order: calls start and finish in the order one sender sent them")
    Stream<DynamicTest> order() {
        return forEach(ALL, "order", path -> {
            ActorRef<Probe> actor = newProbe("order");
            List<Function<Probe, Integer>> actions = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int seq = i;
                long work = ThreadLocalRandom.current().nextLong(0, 2000);
                actions.add(p -> p.call(seq, work));
            }
            CompletableFuture<List<CompletableFuture<?>>> sent = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    sent.complete(path.sendAll(this, actor, actions));
                } catch (Throwable e) {
                    sent.completeExceptionally(e);
                }
            });
            awaitAll(sent.get(WAIT_SECONDS, TimeUnit.SECONDS));
            Probe probe = objectOf(actor);
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                expected.add(i);
            }
            if (path != INLINE_WAITING_ON_POOL_THREAD) {
                assertEquals(expected, probe.started, "calls started out of order");
                assertEquals(expected, probe.finished, "calls finished out of order");
            } else {
                // Each send there is its own pool task, so the sends themselves are not ordered;
                // the calls must still not overlap.
                assertEquals(1, probe.maxInFlight.get());
            }
        });
    }

    @Test
    @DisplayName("2 order: a heavy pool call, then a light tell — the light one runs after the heavy one ends")
    void heavyPoolCallThenLightTell() throws Exception {
        ActorRef<Probe> actor = newProbe("heavy-then-light");
        CompletableFuture<Integer> heavy = actor.ask(p -> p.call(0, 200_000), pool);
        CompletableFuture<Void> light = actor.tell(p -> p.call(1, 0));
        heavy.get(WAIT_SECONDS, TimeUnit.SECONDS);
        light.get(WAIT_SECONDS, TimeUnit.SECONDS);
        Probe probe = objectOf(actor);
        assertEquals(List.of(0, 1), probe.started, "the light tell ran while the heavy pool call was running");
        assertEquals(List.of(0, 1), probe.finished);
    }

    @Test
    @DisplayName("2 order: a slow light tell, then a heavy pool call — the heavy one runs after the light one ends")
    void slowLightTellThenHeavyPoolCall() throws Exception {
        ActorRef<Probe> actor = newProbe("light-then-heavy");
        CompletableFuture<Void> slowLight = actor.tell(p -> p.call(0, 200_000));
        CompletableFuture<Integer> heavy = actor.ask(p -> p.call(1, 1000), pool);
        slowLight.get(WAIT_SECONDS, TimeUnit.SECONDS);
        heavy.get(WAIT_SECONDS, TimeUnit.SECONDS);
        Probe probe = objectOf(actor);
        assertEquals(List.of(0, 1), probe.started, "the heavy pool call started before the slow light tell had ended");
        assertEquals(List.of(0, 1), probe.finished);
    }

    // ------------------------------------------------------------------------------------------
    // 3. Threads
    // ------------------------------------------------------------------------------------------

    @TestFactory
    @DisplayName("3 threads: light calls on the actor's own virtual thread, pool calls on a pool real thread, nothing on commonPool")
    Stream<DynamicTest> threads() {
        List<Path> paths = new ArrayList<>(ALL);
        paths.remove(MIX);
        return forEach(paths, "threads", path -> {
            ActorRef<Probe> actor = newProbe("threads");
            Thread mailboxThread = actor.ask(p -> Thread.currentThread()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            awaitAll(path.sendAll(this, actor, calls(0, 10, 100)));
            Probe probe = objectOf(actor);
            assertEquals(10, probe.threads.size());
            for (int i = 0; i < probe.threads.size(); i++) {
                Thread th = probe.threads.get(i);
                assertFalse(th instanceof ForkJoinWorkerThread, "a call ran on a ForkJoinPool thread: " + th);
                switch (path.runs()) {
                    case ON_MAILBOX_THREAD -> {
                        assertTrue(th.isVirtual(), "a light call ran on a real thread: " + th);
                        assertTrue(th == mailboxThread, "a light call ran off the actor's own virtual thread: " + th);
                    }
                    case ON_POOL_REAL_THREAD -> {
                        assertFalse(th.isVirtual(), "a pool call ran on a virtual thread: " + th);
                        assertTrue(probe.onPoolThread.get(i), "a pool call ran on a real thread outside the pool: " + th);
                    }
                    case ON_EXECUTOR_THREAD -> assertTrue(th != mailboxThread && !probe.onPoolThread.get(i),
                            "an executor call ran on the mailbox or pool thread: " + th);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // 4. The wait is on the actor's virtual thread
    // ------------------------------------------------------------------------------------------

    @TestFactory
    @DisplayName("4 the wait: while a pool or executor call runs, the actor takes no other message")
    Stream<DynamicTest> noOtherMessageWhileOffThreadCallRuns() {
        return forEach(OFF_THE_MAILBOX_THREAD, "waits", path -> {
            ActorRef<Probe> actor = newProbe("waits");
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch lightRan = new CountDownLatch(1);
            AtomicInteger sawLight = new AtomicInteger(-1);
            Function<Probe, Integer> blocking = p -> {
                started.countDown();
                try {
                    // While this runs, the light tell sent below must not run: wait for it at
                    // most half a second, and record whether it came.
                    sawLight.set(lightRan.await(500, TimeUnit.MILLISECONDS) ? 1 : 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sawLight.set(1);
                }
                return 0;
            };
            // Send from another virtual thread, so that this thread is free to send the light
            // tell while the blocking call runs, whichever path the call took.
            CompletableFuture<List<CompletableFuture<?>>> sent = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    sent.complete(path.sendAll(this, actor, List.of(blocking)));
                } catch (Throwable e) {
                    sent.completeExceptionally(e);
                }
            });
            assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));
            CompletableFuture<Void> light = actor.tell(p -> lightRan.countDown());
            awaitAll(sent.get(WAIT_SECONDS, TimeUnit.SECONDS));
            light.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(0, sawLight.get(), "the actor ran another message while the call was running");
            assertEquals(0, lightRan.getCount());
        });
    }

    // ------------------------------------------------------------------------------------------
    // 5. Bounded real threads
    // ------------------------------------------------------------------------------------------

    @TestFactory
    @DisplayName("5 bounded real threads: N actors x M pool calls use at most the pool's real threads")
    Stream<DynamicTest> boundedRealThreads() {
        return forEach(List.of(TELL_POOL, ASK_POOL, INLINE_FROM_POOL_THREAD, INLINE_FROM_OTHER_ACTOR),
                "bounded", path -> {
            List<ActorRef<Probe>> actors = new ArrayList<>();
            List<CompletableFuture<?>> all = new ArrayList<>();
            for (int a = 0; a < 6; a++) {
                ActorRef<Probe> actor = newProbe("bounded-" + a);
                actors.add(actor);
                all.addAll(path.sendAll(this, actor, calls(0, 8, 2000)));
            }
            awaitAll(all);
            Set<Thread> realThreads = ConcurrentHashMap.newKeySet();
            for (ActorRef<Probe> actor : actors) {
                Probe probe = objectOf(actor);
                for (int i = 0; i < probe.threads.size(); i++) {
                    assertTrue(probe.onPoolThread.get(i), "a pool call ran outside the pool");
                    realThreads.add(probe.threads.get(i));
                }
            }
            assertTrue(realThreads.size() <= POOL_SIZE, "pool calls used " + realThreads.size() + " real threads");
        });
    }

    // ------------------------------------------------------------------------------------------
    // 6. No deadlock
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("6 no deadlock: a pool call that waits for another actor's pool call, with one pool real thread")
    void nestedPoolCallWithOnePoolRealThread() throws Exception {
        ActorSystem one = new ActorSystem("one-real-thread", 1);
        try {
            ManagedThreadPool onlyThread = (ManagedThreadPool) one.getManagedThreadPool();
            ActorRef<Object> outer = one.actorOf("outer", new Object());
            ActorRef<Object> inner = one.actorOf("inner", new Object());
            int answer = outer.ask(o -> inner.ask(i -> 41, onlyThread).join() + 1, onlyThread)
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(42, answer);
        } finally {
            one.terminate();
        }
    }

    @Test
    @DisplayName("6 no deadlock: every pool real thread waits for an actor whose earlier job came from a virtual thread")
    void everyPoolRealThreadWaitsBehindAnEarlierJob() throws Exception {
        ActorRef<Probe> target = newProbe("target");
        CountDownLatch bothBusy = new CountDownLatch(POOL_SIZE);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<?>> waiters = new ArrayList<>();
        for (int w = 0; w < POOL_SIZE; w++) {
            int seq = 10 + w;
            ActorRef<Object> waiter = system.actorOf("waiter-" + w, new Object());
            waiters.add(waiter.ask(o -> {
                bothBusy.countDown();
                try {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return target.ask(p -> p.call(seq, 0), pool).join();
            }, pool));
        }
        assertTrue(bothBusy.await(WAIT_SECONDS, TimeUnit.SECONDS), "both pool real threads should be busy");
        // An earlier job for the target, sent from this test's thread while no pool real thread is free.
        CompletableFuture<Integer> earlier = target.ask(p -> p.call(0, 0), pool);
        go.countDown();
        earlier.get(WAIT_SECONDS, TimeUnit.SECONDS);
        awaitAll(waiters);
        assertEquals(1, objectOf(target).maxInFlight.get());
    }

    @Test
    @DisplayName("6 no deadlock: an actor asking itself with a plain ask, from inside its own message")
    void actorAsksItselfPlainAsk() throws Exception {
        AtomicInteger depth = new AtomicInteger();
        ActorRef<AtomicInteger>[] self = new ActorRef[1];
        self[0] = system.actorOf("self-plain", depth);
        int answer = self[0].ask(d -> self[0].ask(inner -> 41).join() + 1).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(42, answer);
    }

    @Test
    @DisplayName("6 no deadlock: an actor asking itself on the pool, from inside its own pool action")
    void actorAsksItselfOnThePoolFromItsPoolAction() throws Exception {
        ActorRef<Object>[] self = new ActorRef[1];
        self[0] = system.actorOf("self-pool", new Object());
        int answer = self[0].ask(o -> self[0].ask(inner -> 41, pool).join() + 1, pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(42, answer);
    }

    @Test
    @DisplayName("6 no deadlock: an actor asking itself on the pool, from inside its own plain message")
    void actorAsksItselfOnThePoolFromItsPlainMessage() throws Exception {
        ActorRef<Object>[] self = new ActorRef[1];
        self[0] = system.actorOf("self-mixed", new Object());
        int answer = self[0].ask(o -> self[0].ask(inner -> {
            assertTrue(pool.ownsCurrentThread(), "the nested pool call should run on a pool real thread");
            return 41;
        }, pool).join() + 1).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(42, answer);
    }

    @Test
    @DisplayName("6 a tell to itself from inside its own message is queued after that message")
    void tellToItselfIsQueued() throws Exception {
        ActorRef<Probe>[] self = new ActorRef[1];
        self[0] = newProbe("self-tell");
        CompletableFuture<?>[] queued = new CompletableFuture[1];
        self[0].tell(p -> {
            p.call(0, 0);
            queued[0] = self[0].tell(q -> q.call(2, 0));
            p.call(1, 0);
        }).get(WAIT_SECONDS, TimeUnit.SECONDS);
        queued[0].get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of(0, 1, 2), objectOf(self[0]).started);
    }

    /**
     * A known limit, not a guarantee: two actors that ask each other from inside their own
     * messages wait for each other for ever, because each actor takes the next message only after
     * the current one has ended. This test pins that behaviour so that a change to it is seen.
     */
    @Test
    @DisplayName("known limit: two actors asking each other from inside their messages do not complete")
    void knownLimit_crossActorAskCycle_doesNotComplete() throws Exception {
        ActorRef<Object>[] b = new ActorRef[1];
        ActorRef<Object> a = system.actorOf("cycle-a", new Object());
        b[0] = system.actorOf("cycle-b", new Object());
        CompletableFuture<Integer> cycle = a.ask(x -> b[0].ask(y -> a.ask(z -> 1).join()).join());
        assertThrows(TimeoutException.class, () -> cycle.get(500, TimeUnit.MILLISECONDS),
                "the cycle completed; if that is now intended, update the documented limit");
        a.close();
        b[0].close();
    }

    // ------------------------------------------------------------------------------------------
    // 7. tellNow / askNow
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("7 tellNow and askNow run at the same time as the message the actor is running")
    void tellNowAndAskNowRunConcurrently() throws Exception {
        ActorRef<Probe> actor = newProbe("now");
        CountDownLatch inMessage = new CountDownLatch(1);
        CountDownLatch nowRan = new CountDownLatch(2);
        CompletableFuture<Boolean> message = actor.ask(p -> {
            p.inFlight.incrementAndGet();
            inMessage.countDown();
            try {
                return nowRan.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                p.inFlight.decrementAndGet();
            }
        });
        assertTrue(inMessage.await(WAIT_SECONDS, TimeUnit.SECONDS));
        CompletableFuture<Void> told = actor.tellNow(p -> { p.call(1, 0); nowRan.countDown(); });
        CompletableFuture<Integer> asked = actor.askNow(p -> { int r = p.call(2, 0); nowRan.countDown(); return r; });
        assertTrue(message.get(WAIT_SECONDS, TimeUnit.SECONDS), "tellNow/askNow did not run while the message ran");
        told.get(WAIT_SECONDS, TimeUnit.SECONDS);
        asked.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(objectOf(actor).maxInFlight.get() >= 2, "tellNow/askNow did not overlap the running message");
    }

    // ------------------------------------------------------------------------------------------
    // 8. Stress, and chat-89's lost update
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("8 stress: many actors, many senders, every path mixed — exclusion and per-sender order hold")
    void stress() throws Exception {
        Path[] paths = { TELL, ASK, TELL_POOL, ASK_POOL, TELL_EXECUTOR, ASK_EXECUTOR, INLINE_FROM_POOL_THREAD };
        int actors = 12, senders = 3, perSender = 40;
        List<ActorRef<Probe>> refs = new ArrayList<>();
        List<CompletableFuture<List<CompletableFuture<?>>>> sent = new ArrayList<>();
        for (int a = 0; a < actors; a++) {
            ActorRef<Probe> actor = newProbe("stress-" + a);
            refs.add(actor);
            for (int s = 0; s < senders; s++) {
                int sender = s;
                CompletableFuture<List<CompletableFuture<?>>> f = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        List<CompletableFuture<?>> futures = new ArrayList<>();
                        for (int i = 0; i < perSender; i++) {
                            int seq = sender * 1000 + i;
                            long work = ThreadLocalRandom.current().nextLong(0, 300);
                            Path p = paths[ThreadLocalRandom.current().nextInt(paths.length)];
                            futures.addAll(p.sendAll(this, actor, List.of(x -> x.call(seq, work))));
                        }
                        f.complete(futures);
                    } catch (Throwable e) {
                        f.completeExceptionally(e);
                    }
                });
                sent.add(f);
            }
        }
        for (CompletableFuture<List<CompletableFuture<?>>> f : sent) {
            awaitAll(f.get(WAIT_SECONDS * 3, TimeUnit.SECONDS));
        }
        for (ActorRef<Probe> actor : refs) {
            Probe probe = objectOf(actor);
            assertEquals(senders * perSender, probe.finished.size());
            assertEquals(1, probe.maxInFlight.get(), "calls overlapped in " + actor.getName());
            for (int s = 0; s < senders; s++) {
                int sender = s;
                List<Integer> ofSender = probe.started.stream().filter(q -> q / 1000 == sender).toList();
                List<Integer> sorted = new ArrayList<>(ofSender);
                Collections.sort(sorted);
                assertEquals(sorted, ofSender, "sender " + s + "'s calls started out of order in " + actor.getName());
            }
        }
    }

    @Test
    @DisplayName("8 chat-89 S12: mailbox tells and pool asks on one unsynchronised field lose no update")
    void mailboxAndPoolLoseNoUpdate() throws Exception {
        ActorRef<Probe> actor = newProbe("lost-update");
        int per = 20_000;
        CompletableFuture<?>[] last = new CompletableFuture[2];
        Thread mailbox = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < per; i++) last[0] = actor.tell(p -> p.plainCounter++);
        });
        Thread viaPool = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < per; i++) last[1] = actor.ask(p -> p.plainCounter++, pool);
        });
        mailbox.join();
        viaPool.join();
        last[0].get(WAIT_SECONDS * 3, TimeUnit.SECONDS);
        last[1].get(WAIT_SECONDS * 3, TimeUnit.SECONDS);
        // Wait for everything already sent on both paths.
        actor.ask(p -> 0).get(WAIT_SECONDS, TimeUnit.SECONDS);
        actor.ask(p -> 0, pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2 * per, objectOf(actor).plainCounter, "updates were lost");
    }
}
