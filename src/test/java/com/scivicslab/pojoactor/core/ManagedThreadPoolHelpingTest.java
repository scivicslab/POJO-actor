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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the wait of a real thread of a {@link ManagedThreadPool} that runs queued tasks of its pool
 * while it waits for a future ({@link ManagedThreadPool#awaitHelping(CompletableFuture)}), and the
 * futures of {@code ActorRef.ask(action, pool)} and {@code tell(action, pool)} that wait that way.
 *
 * <p>Each rule of the wait has tests here that fail when the rule is removed:</p>
 * <ol>
 *   <li>no deadlock when every real thread of the pool waits for an actor's answer;</li>
 *   <li>the depth limit of tasks nested in one real thread's stack;</li>
 *   <li>per actor, the waiting real thread takes no task of an actor whose turn it holds, and keeps
 *       the order of an actor's tasks, so that one thread at a time is inside an actor's object;</li>
 *   <li>an actor that asks itself while its real thread helps runs the ask nested;</li>
 *   <li>the time limit of the variant that takes a {@link Duration};</li>
 *   <li>the interrupt status of the waiting caller, and {@code ownsCurrentThread()} inside a task
 *       run by the waiting real thread.</li>
 * </ol>
 *
 * <p>The interleavings are forced with latches. Every wait in the tests has a time limit, and every
 * test has a {@link Timeout}, so a broken rule ends a test as a failure and not as a hang.</p>
 */
@Tag("ManagedThreadPool_260508_oo01")
@Tag("HelpingWaitOfManagedThreadPool_261123_oo01")
@DisplayName("ManagedThreadPool — a real thread that waits runs queued tasks of its pool")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ManagedThreadPoolHelpingTest {

    private static final long WAIT_SECONDS = 10;

    private ActorSystem system;
    private ManagedThreadPool pool;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    private void newSystem(int realThreads) {
        system = new ActorSystem("helping-" + realThreads, realThreads);
        pool = (ManagedThreadPool) system.getManagedThreadPool();
    }

    /** The object the tests send to: it counts the calls inside it at once and records their order and thread. */
    static final class Probe {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<Integer> started = Collections.synchronizedList(new ArrayList<>());
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());

        int call(int seq, long workMicros) {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            started.add(seq);
            threads.add(Thread.currentThread());
            if (workMicros > 0) {
                try {
                    TimeUnit.MICROSECONDS.sleep(workMicros);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            inFlight.decrementAndGet();
            return seq;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "a latch was not released in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <V> V get(CompletableFuture<V> future) throws Exception {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            dumpPoolThreads();
            throw timeout;
        }
    }

    /** Prints the stacks of the pool's real threads, to show where a test that did not complete is stuck. */
    private static void dumpPoolThreads() {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (thread.getName().startsWith("managed-pool-")) {
                StringBuilder text = new StringBuilder("STUCK ").append(thread.getName()).append(' ').append(thread.getState());
                int shown = 0;
                for (StackTraceElement frame : stack) {
                    if (shown++ < 40) {
                        text.append("\n    at ").append(frame);
                    }
                }
                System.out.println(text);
            }
        });
    }

    /** Spins, without sleeping, until the condition holds or the time limit passes. */
    private static boolean spinUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.onSpinWait();
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // 1. No deadlock when every real thread of the pool waits
    // ------------------------------------------------------------------------------------------

    /**
     * The worst case: every real thread of the pool is inside an action that waits for an answer from
     * one actor, and the first message of that actor, sent earlier by a thread that is not a real
     * thread of the pool, still waits in the pool's queue because no real thread was free to run it.
     */
    private void everyRealThreadWaitsBehindAnEarlierMessage(int realThreads) throws Exception {
        ActorRef<Probe> target = system.actorOf("target", new Probe());
        CountDownLatch allInside = new CountDownLatch(realThreads);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> waiting = new ArrayList<>();
        for (int i = 0; i < realThreads; i++) {
            int seq = 100 + i;
            waiting.add(CompletableFuture.supplyAsync(() -> {
                allInside.countDown();
                await(go);
                return target.ask(p -> p.call(seq, 0), pool).join();
            }, pool));
        }
        await(allInside);
        // The earlier message: its turn job waits in the pool's queue, because every real thread is taken.
        CompletableFuture<Integer> earlier = target.ask(p -> p.call(0, 0), pool);
        assertTrue(spinUntil(() -> pool.getPendingJobCountForActor("target") == 1),
                "the earlier message should reach the pool's queue");
        go.countDown();

        assertEquals(0, get(earlier));
        for (CompletableFuture<Integer> w : waiting) {
            get(w);
        }
        Probe probe = target.ask(p -> p, pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, probe.started.get(0), "the earlier message must run first");
        assertEquals(realThreads + 1, probe.started.size());
        assertEquals(1, probe.maxInFlight.get(), "more than one call was inside the object at once");
    }

    @TestFactory
    @DisplayName("1 no deadlock: every real thread of the pool waits behind an earlier message of the actor it asks")
    Stream<DynamicTest> everyRealThreadWaits() {
        return Stream.of(1, 2, 4).map(n -> DynamicTest.dynamicTest(n + " real thread(s)", () -> {
            newSystem(n);
            try {
                everyRealThreadWaitsBehindAnEarlierMessage(n);
            } finally {
                system.terminate();
                system = null;
            }
        }));
    }

    @Test
    @DisplayName("1 no deadlock: the same, with the pool size the machine gives (run under -XX:ActiveProcessorCount=1, 2 and 4)")
    void everyRealThreadWaitsWithTheDefaultPoolSize() throws Exception {
        system = new ActorSystem("helping-default");
        pool = (ManagedThreadPool) system.getManagedThreadPool();
        everyRealThreadWaitsBehindAnEarlierMessage(Runtime.getRuntime().availableProcessors());
    }

    @TestFactory
    @DisplayName("1 no deadlock: every real thread waits in a chain of nested asks through different actors")
    Stream<DynamicTest> everyRealThreadWaitsInNestedAsks() {
        return Stream.of(1, 2, 4).map(n -> DynamicTest.dynamicTest(n + " real thread(s)", () -> {
            newSystem(n);
            try {
                int depth = 6;
                CountDownLatch allInside = new CountDownLatch(n);
                List<CompletableFuture<Integer>> runs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    // each run uses its own set of actors, so the chains do not wait for each other
                    List<ActorRef<Object>> mine = new ArrayList<>();
                    for (int level = 0; level < depth; level++) {
                        mine.add(system.actorOf("run-" + i + "-" + level, new Object()));
                    }
                    runs.add(CompletableFuture.supplyAsync(() -> {
                        allInside.countDown();
                        await(allInside);
                        return nestedAsk(mine, 0);
                    }, pool));
                }
                for (CompletableFuture<Integer> run : runs) {
                    assertEquals(depth, get(run));
                }
            } finally {
                system.terminate();
                system = null;
            }
        }));
    }

    private int nestedAsk(List<ActorRef<Object>> chain, int level) {
        if (level == chain.size()) {
            return 0;
        }
        return chain.get(level).ask(o -> nestedAsk(chain, level + 1), pool).join() + 1;
    }

    // ------------------------------------------------------------------------------------------
    // 2. The depth limit
    // ------------------------------------------------------------------------------------------

    /**
     * One real thread; every task waits for an answer of its own actor, whose mailbox is held by an
     * earlier message until the test lets it go. The one real thread runs the next queued task, which
     * waits the same way, and so on, nested in its stack, until the limit is reached.
     */
    private List<Integer> manyTasksThatWaitForHeldMailboxes(int tasks, CountDownLatch holdMailboxes) throws Exception {
        List<CompletableFuture<Integer>> all = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            int n = i;
            ActorRef<Object> actor = system.actorOf("held-" + i, new Object());
            actor.tell(o -> await(holdMailboxes));
            all.add(CompletableFuture.supplyAsync(() -> actor.ask(o -> n, pool).join(), pool));
        }
        assertTrue(spinUntil(() -> pool.deepestHelpingDepth() >= ManagedThreadPool.MAX_HELPING_DEPTH),
                "the waiting real thread should have nested up to the limit, was " + pool.deepestHelpingDepth());
        holdMailboxes.countDown();
        List<Integer> results = new ArrayList<>();
        for (CompletableFuture<Integer> f : all) {
            results.add(get(f));
        }
        return results;
    }

    @Test
    @DisplayName("2 depth: a real thread nests at most the limit of tasks, and every task still completes when the limit is reached")
    void depthLimitIsReachedAndEverythingCompletes() throws Exception {
        newSystem(1);
        int tasks = 2000;
        List<Integer> results = manyTasksThatWaitForHeldMailboxes(tasks, new CountDownLatch(1));
        for (int i = 0; i < tasks; i++) {
            assertEquals(i, results.get(i));
        }
        // the action handed back to the real thread that sent it runs inside the deepest waiter and is
        // not counted against the limit, so one level more can be seen
        assertTrue(pool.deepestHelpingDepth() <= ManagedThreadPool.MAX_HELPING_DEPTH + 1,
                "nested " + pool.deepestHelpingDepth() + " deep, the limit is " + ManagedThreadPool.MAX_HELPING_DEPTH);
    }

    /**
     * One real thread at the depth limit waits for the answer of actor A, whose message is behind an
     * earlier message of A that an outer waiter of the same real thread sent. That earlier message
     * can run only on this real thread, and only if the waiter at the limit runs it.
     */
    @Test
    @DisplayName("2 depth: at the limit, a waiter for an actor's answer still runs that actor's queued task, so the earlier message of that actor is not stuck below it")
    void atTheLimitTheTasksOfTheActorWaitedForAreStillRun() throws Exception {
        newSystem(1);
        int levels = ManagedThreadPool.MAX_HELPING_DEPTH + 1;       // task 0 on the worker, tasks 1..limit nested
        CountDownLatch holdMailboxes = new CountDownLatch(1);
        ActorRef<Probe> shared = system.actorOf("shared", new Probe());
        shared.tell(p -> await(holdMailboxes));
        List<CompletableFuture<Integer>> all = new ArrayList<>();
        for (int i = 0; i < levels; i++) {
            int n = i;
            // the first and the last task ask the shared actor: the last message is behind the first
            ActorRef<Probe> target = (i == 0 || i == levels - 1) ? shared : system.actorOf("own-" + i, new Probe());
            if (target != shared) {
                target.tell(p -> await(holdMailboxes));
            }
            all.add(CompletableFuture.supplyAsync(() -> target.ask(p -> p.call(n, 0), pool).join(), pool));
        }
        assertTrue(spinUntil(() -> pool.deepestHelpingDepth() >= ManagedThreadPool.MAX_HELPING_DEPTH),
                "the real thread should have nested up to the limit");
        holdMailboxes.countDown();
        for (CompletableFuture<Integer> f : all) {
            get(f);
        }
        Probe probe = shared.ask(p -> p, pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of(0, levels - 1), probe.started, "the shared actor's messages must run in the order sent");
        assertEquals(1, probe.maxInFlight.get());
    }

    /** A chain of {@code length} tasks, each of which queues the next and waits for it. */
    private CompletableFuture<Integer> chainOfWaitingTasks(int index, int length) {
        return CompletableFuture.supplyAsync(() -> {
            if (index + 1 == length) {
                return 1;
            }
            CompletableFuture<Integer> next = chainOfWaitingTasks(index + 1, length);
            try {
                pool.awaitHelping(next);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return next.join() + 1;
        }, pool);
    }

    @Test
    @DisplayName("2 depth: on one real thread, a chain of waiting tasks as long as the limit allows completes")
    void chainWithinTheLimitCompletes() throws Exception {
        newSystem(1);
        int length = ManagedThreadPool.MAX_HELPING_DEPTH + 1;
        assertEquals(length, get(chainOfWaitingTasks(0, length)));
    }

    /**
     * A limit of the depth, not a guarantee: on one real thread, a chain of waiting tasks that each
     * need the next one to run is one task longer than the limit allows, and the last task is not
     * run by anyone, because the one real thread stands at the limit and waits without running it.
     */
    @Test
    @DisplayName("known limit: on one real thread, a chain of waiting tasks one longer than the limit does not complete")
    void knownLimit_chainBeyondTheLimitDoesNotComplete() throws Exception {
        newSystem(1);
        CompletableFuture<Integer> chain = chainOfWaitingTasks(0, ManagedThreadPool.MAX_HELPING_DEPTH + 2);
        assertThrows(TimeoutException.class, () -> chain.get(500, TimeUnit.MILLISECONDS),
                "the chain completed; if the limit now allows it, update the documented limit");
    }

    @Test
    @DisplayName("2 depth: how many stack frames one nested task costs, and what a real thread's stack holds (measured, printed)")
    void measureFramesPerLevel() throws Exception {
        newSystem(1);
        int tasks = ManagedThreadPool.MAX_HELPING_DEPTH + 8;
        List<Integer> frames = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch hold = new CountDownLatch(1);
        List<CompletableFuture<Integer>> all = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            ActorRef<Object> actor = system.actorOf("frames-" + i, new Object());
            actor.tell(o -> await(hold));
            all.add(CompletableFuture.supplyAsync(() -> {
                frames.add(new Throwable().getStackTrace().length);
                return actor.ask(o -> 0, pool).join();
            }, pool));
        }
        assertTrue(spinUntil(() -> pool.deepestHelpingDepth() >= ManagedThreadPool.MAX_HELPING_DEPTH));
        hold.countDown();
        for (CompletableFuture<Integer> f : all) {
            get(f);
        }
        List<Integer> sample = new ArrayList<>(frames).subList(0, ManagedThreadPool.MAX_HELPING_DEPTH + 1);
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int i = 1; i < sample.size(); i++) {
            int step = sample.get(i) - sample.get(i - 1);
            min = Math.min(min, step);
            max = Math.max(max, step);
        }
        // what a real thread of the pool can hold: recurse until the stack overflows
        int capacity = CompletableFuture.supplyAsync(() -> recurseUntilOverflow(0), pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
        System.out.println("MEASURED helping frames per nested task: min=" + min + " max=" + max
                + "; frames at the limit=" + sample.get(sample.size() - 1)
                + "; plain recursion overflows a real thread of the pool at about " + capacity + " frames");
        assertTrue(min > 0);
    }

    private static int recurseUntilOverflow(int depth) {
        try {
            return recurseUntilOverflow(depth + 1);
        } catch (StackOverflowError overflow) {
            return depth;
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. Per actor: exclusion and order
    // ------------------------------------------------------------------------------------------

    /** Holds the pool's other real thread, so that nothing but the real thread under test can run a queued task. */
    private CompletableFuture<Void> holdTheOtherRealThread(CountDownLatch release) {
        CountDownLatch busy = new CountDownLatch(1);
        CompletableFuture<Void> blocker = CompletableFuture.runAsync(() -> {
            busy.countDown();
            await(release);
        }, pool);
        await(busy);
        return blocker;
    }

    @Test
    @DisplayName("3 per actor: a real thread inside an actor's action does not run other queued tasks while it waits for a future that is not an actor's answer")
    void turnHolderRunsNoUnrelatedTaskWhileItWaitsForAPlainFuture() throws Exception {
        newSystem(2);
        ActorRef<Probe> x = system.actorOf("x", new Probe());
        CountDownLatch otherRelease = new CountDownLatch(1);
        CompletableFuture<Void> blocker = holdTheOtherRealThread(otherRelease);

        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        List<String> ranWhileWaiting = new ArrayList<>();
        boolean[] answered = new boolean[1];
        CompletableFuture<Integer> action = x.ask(p -> {
            pool.submitForActor("x", () -> ran.add("x"));           // a task of the actor whose turn is held
            pool.submitForActor("y", () -> ran.add("y"));           // a task of another actor
            CompletableFuture<Integer> plain = CompletableFuture.supplyAsync(() -> {   // a task of no actor
                ran.add("plain");
                return 7;
            }, pool);
            try {
                answered[0] = pool.awaitHelping(plain, Duration.ofMillis(300));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ranWhileWaiting.addAll(ran);
            return 1;
        }, pool);
        assertEquals(1, get(action));
        assertEquals(List.of(), ranWhileWaiting,
                "a real thread that holds an actor's turn ran a task that its wait does not depend on");
        assertFalse(answered[0]);
        otherRelease.countDown();
        get(blocker);
        assertTrue(spinUntil(() -> ran.size() == 3), "the tasks left in the queue must still run, by the other real threads");
    }

    @Test
    @DisplayName("3 per actor: a real thread that holds no turn takes the queued tasks of actors and of no actor in the order they were queued")
    void turnFreeWaiterTakesTasksInQueueOrder() throws Exception {
        newSystem(2);
        CountDownLatch otherRelease = new CountDownLatch(1);
        CompletableFuture<Void> blocker = holdTheOtherRealThread(otherRelease);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<List<String>> waiter = CompletableFuture.supplyAsync(() -> {
            pool.submitForActor("x", () -> ran.add("x-first"));
            pool.submitForActor("y", () -> ran.add("y"));
            pool.submitForActor("x", () -> ran.add("x-second"));
            CompletableFuture<Integer> last = CompletableFuture.supplyAsync(() -> {
                ran.add("plain");
                return 1;
            }, pool);
            try {
                pool.awaitHelping(last);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ArrayList<>(ran);
        }, pool);
        assertEquals(List.of("x-first", "y", "x-second", "plain"), get(waiter));
        otherRelease.countDown();
        get(blocker);
    }

    @Test
    @DisplayName("3 per actor: the thread that holds the turn of x and of y, asking x from inside y's action while it waits, runs the ask nested on itself")
    void askToAHeldActorFromAnotherHeldActorRunsNested() throws Exception {
        newSystem(2);
        ActorRef<Probe> x = system.actorOf("x", new Probe());
        ActorRef<Probe> y = system.actorOf("y", new Probe());
        CompletableFuture<Thread> action = x.ask(p -> {
            Thread outer = Thread.currentThread();
            p.call(1, 0);
            y.ask(q -> x.ask(r -> r.call(2, 0), pool).join(), pool).join();
            return outer;
        }, pool);
        Thread outer = get(action);
        Probe probe = x.ask(p -> p, pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of(1, 2), probe.started);
        assertEquals(List.of(outer, outer), probe.threads, "the nested ask ran on another thread than the one inside the actor");
        assertEquals(1, probe.maxInFlight.get(), "more than one thread was inside the actor at once");
    }

    /**
     * Two real threads each hold an actor's turn (a and b) and wait for the answer of a leaf actor c,
     * whose mailbox is held for the moment. Two unrelated tasks wait in the queue: one asks b, the
     * other asks a. If a real thread that holds a turn ran an unrelated task, the first real thread
     * would wait, on top of a's held turn, for b, while the second waits, on top of b's held turn, for
     * a: each waits for an actor whose turn the other holds below, and neither can unwind.
     */
    @Test
    @DisplayName("3 per actor: two real threads that hold the turns of a and of b do not run unrelated tasks that ask b and a, so they do not wait for each other")
    void turnHoldersDoNotEntangleThroughUnrelatedTasks() throws Exception {
        newSystem(2);
        ActorRef<Probe> a = system.actorOf("a", new Probe());
        ActorRef<Probe> b = system.actorOf("b", new Probe());
        ActorRef<Probe> c = system.actorOf("c", new Probe());
        CountDownLatch holdC = new CountDownLatch(1);
        c.tell(p -> await(holdC));
        CountDownLatch bothWait = new CountDownLatch(2);
        CompletableFuture<Integer> fromA = a.ask(p -> {
            bothWait.countDown();
            return c.ask(q -> q.call(1, 0), pool).join();
        }, pool);
        CompletableFuture<Integer> fromB = b.ask(p -> {
            bothWait.countDown();
            return c.ask(q -> q.call(2, 0), pool).join();
        }, pool);
        await(bothWait);
        // the unrelated tasks: they wait in the queue while both real threads wait inside a turn
        CompletableFuture<Integer> asksB = CompletableFuture.supplyAsync(() -> b.ask(q -> q.call(3, 0), pool).join(), pool);
        CompletableFuture<Integer> asksA = CompletableFuture.supplyAsync(() -> a.ask(q -> q.call(4, 0), pool).join(), pool);
        holdC.countDown();
        get(fromA);
        get(fromB);
        get(asksB);
        get(asksA);
        assertEquals(1, a.ask(p -> p, pool).get(WAIT_SECONDS, TimeUnit.SECONDS).maxInFlight.get());
        assertEquals(1, b.ask(p -> p, pool).get(WAIT_SECONDS, TimeUnit.SECONDS).maxInFlight.get());
    }

    /**
     * Many actors, many tasks that wait in the pool's real threads for answers of actors that are
     * higher in a fixed order (so that no two actors ask each other), on 1, 2 and 4 real threads: at
     * most one thread is inside each actor's object at any time, and one sender's messages to one
     * actor start in the order they were sent.
     */
    @TestFactory
    @DisplayName("3 per actor: stress — while real threads wait for each other's actors, at most one thread is inside each actor")
    Stream<DynamicTest> exclusionAndOrderWhileHelping() {
        return Stream.of(1, 2, 4).map(n -> DynamicTest.dynamicTest(n + " real thread(s)", () -> {
            newSystem(n);
            try {
                int actors = 10;
                int tasks = 400;
                List<ActorRef<Probe>> refs = new ArrayList<>();
                for (int a = 0; a < actors; a++) {
                    refs.add(system.actorOf("stress-" + a, new Probe()));
                }
                List<CompletableFuture<Integer>> all = new ArrayList<>();
                for (int t = 0; t < tasks; t++) {
                    int task = t;
                    all.add(CompletableFuture.supplyAsync(() -> {
                        int first = ThreadLocalRandom.current().nextInt(actors - 1);
                        int second = first + 1 + ThreadLocalRandom.current().nextInt(actors - first - 1);
                        // an ask to a lower actor, whose action asks a higher one: no two actors ask each other
                        return refs.get(first).ask(p -> {
                            p.call(task, ThreadLocalRandom.current().nextLong(0, 200));
                            return refs.get(second).ask(q -> q.call(task, ThreadLocalRandom.current().nextLong(0, 200)), pool).join();
                        }, pool).join();
                    }, pool));
                }
                for (CompletableFuture<Integer> f : all) {
                    get(f);
                }
                for (ActorRef<Probe> ref : refs) {
                    Probe probe = ref.ask(p -> p, pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
                    assertEquals(1, probe.maxInFlight.get(), "more than one thread was inside " + ref.getName() + " at once");
                }
                System.out.println("MEASURED deepest nesting while helping, " + n + " real thread(s), "
                        + tasks + " tasks over " + actors + " actors: " + pool.deepestHelpingDepth()
                        + " (limit " + ManagedThreadPool.MAX_HELPING_DEPTH + ")");
            } finally {
                system.terminate();
                system = null;
            }
        }));
    }

    // ------------------------------------------------------------------------------------------
    // 4. The time limit
    // ------------------------------------------------------------------------------------------

    private <V> V onPoolThread(java.util.concurrent.Callable<V> work) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("4 time limit: returns false after the time when the future has not completed")
    void timedWaitReturnsFalseWhenTheTimeRunsOut() throws Exception {
        newSystem(1);
        long[] waitedMillis = new long[1];
        boolean done = onPoolThread(() -> {
            long start = System.nanoTime();
            boolean result = pool.awaitHelping(new CompletableFuture<>(), Duration.ofMillis(150));
            waitedMillis[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return result;
        });
        assertFalse(done);
        assertTrue(waitedMillis[0] >= 140, "returned after only " + waitedMillis[0] + " ms");
        assertTrue(waitedMillis[0] < 5000, "returned after " + waitedMillis[0] + " ms");
    }

    @Test
    @DisplayName("4 time limit: returns true as soon as the future completes within the time")
    void timedWaitReturnsTrueWhenTheFutureCompletes() throws Exception {
        newSystem(2);
        CompletableFuture<Integer> future = new CompletableFuture<>();
        CountDownLatch inside = new CountDownLatch(1);
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                inside.countDown();
                return pool.awaitHelping(future, Duration.ofSeconds(WAIT_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, pool);
        await(inside);
        future.complete(1);
        assertTrue(get(waiter));
    }

    @Test
    @DisplayName("4 time limit: a zero or negative time does not wait, and answers whether the future has completed")
    void zeroOrNegativeTimeDoesNotWait() throws Exception {
        newSystem(1);
        CompletableFuture<Integer> done = CompletableFuture.completedFuture(1);
        assertTrue(onPoolThread(() -> pool.awaitHelping(done, Duration.ZERO)));
        assertFalse(onPoolThread(() -> pool.awaitHelping(new CompletableFuture<>(), Duration.ZERO)));
        assertFalse(onPoolThread(() -> pool.awaitHelping(new CompletableFuture<>(), Duration.ofSeconds(-5))));
    }

    @Test
    @DisplayName("4 time limit: the limit is checked between tasks; a task that has started is not cut short")
    void timeLimitIsCheckedBetweenTasks() throws Exception {
        newSystem(1);
        CountDownLatch taskInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean taskEnded = new AtomicBoolean();
        boolean[] taskHadEndedAtReturn = new boolean[1];
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
            CompletableFuture.runAsync(() -> {
                taskInside.countDown();
                await(release);
                taskEnded.set(true);
            }, pool);
            try {
                boolean result = pool.awaitHelping(new CompletableFuture<>(), Duration.ofMillis(50));
                taskHadEndedAtReturn[0] = taskEnded.get();
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }, pool);
        await(taskInside);
        // the 50 ms are over while the task runs; the call must still be inside it
        assertThrows(TimeoutException.class, () -> waiter.get(300, TimeUnit.MILLISECONDS));
        release.countDown();
        assertFalse(get(waiter));
        assertTrue(taskHadEndedAtReturn[0], "the call returned while the task it was running had not ended");
    }

    @Test
    @DisplayName("4 time limit: with many short tasks queued, the limit is checked before every task, so the call does not run them all")
    void timeLimitEndsTheWaitBeforeAllQueuedTasksRan() throws Exception {
        newSystem(1);
        int queued = 300;
        AtomicInteger ran = new AtomicInteger();
        long[] waitedMillis = new long[1];
        boolean completed = onPoolThread(() -> {
            for (int i = 0; i < queued; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        TimeUnit.MILLISECONDS.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ran.incrementAndGet();
                }, pool);
            }
            long start = System.nanoTime();
            boolean result = pool.awaitHelping(new CompletableFuture<>(), Duration.ofMillis(200));
            waitedMillis[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return result;
        });
        assertFalse(completed);
        assertTrue(ran.get() > 0, "the waiting real thread should have run some of the queued tasks");
        assertTrue(ran.get() < queued, "the call ran all " + queued + " queued tasks, past its time limit");
        assertTrue(waitedMillis[0] < 2000, "the call returned after " + waitedMillis[0] + " ms");
    }

    @Test
    @DisplayName("4 time limit: a time longer than a long holds in nanoseconds waits without a limit and completes")
    void aTimeTooLongForNanosecondsWaitsWithoutALimit() throws Exception {
        newSystem(2);
        CompletableFuture<Integer> future = new CompletableFuture<>();
        CountDownLatch inside = new CountDownLatch(1);
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                inside.countDown();
                return pool.awaitHelping(future, Duration.ofSeconds(Long.MAX_VALUE / 2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, pool);
        await(inside);
        future.complete(1);
        assertTrue(get(waiter));
    }

    @Test
    @DisplayName("4 time limit: called from a thread outside the pool, the time limit applies as well")
    void timedWaitOutsideThePool() throws Exception {
        newSystem(1);
        assertFalse(pool.awaitHelping(new CompletableFuture<>(), Duration.ofMillis(100)));
        CompletableFuture<Integer> done = CompletableFuture.completedFuture(1);
        assertTrue(pool.awaitHelping(done, Duration.ofMillis(100)));
    }

    // ------------------------------------------------------------------------------------------
    // 5. Interrupt status and the thread
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("5 interrupt: an interrupt of the waiting real thread ends an interruptible wait with an InterruptedException and clears the status")
    void interruptEndsAnInterruptibleWait() throws Exception {
        newSystem(1);
        CountDownLatch inside = new CountDownLatch(1);
        AtomicReference<Thread> waiter = new AtomicReference<>();
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            waiter.set(Thread.currentThread());
            inside.countDown();
            try {
                pool.awaitHelping(new CompletableFuture<>());
                return "returned";
            } catch (InterruptedException e) {
                return "interrupted, status " + Thread.currentThread().isInterrupted();
            }
        }, pool);
        await(inside);
        waiter.get().interrupt();
        assertEquals("interrupted, status false", get(result));
    }

    @Test
    @DisplayName("5 interrupt: an interrupt left set by a task the waiting real thread ran ends the wait, as an interrupt of the waiting caller")
    void interruptLeftByAHelpedTaskEndsTheWait() throws Exception {
        newSystem(1);
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            CompletableFuture.runAsync(() -> Thread.currentThread().interrupt(), pool);
            try {
                pool.awaitHelping(new CompletableFuture<>());
                return "returned";
            } catch (InterruptedException e) {
                return "interrupted, status " + Thread.currentThread().isInterrupted();
            }
        }, pool);
        assertEquals("interrupted, status false", get(result));
    }

    @Test
    @DisplayName("5 interrupt: a wait that is not interruptible keeps waiting, runs tasks with the status cleared, and sets the status again at the end")
    void uninterruptibleWaitRemembersTheInterrupt() throws Exception {
        newSystem(1);
        AtomicBoolean taskSawInterrupt = new AtomicBoolean(true);
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
            CompletableFuture<Integer> awaited = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                taskSawInterrupt.set(Thread.currentThread().isInterrupted());
                awaited.complete(1);
            }, pool);
            Thread.currentThread().interrupt();                       // the caller was interrupted before it waited
            try {
                pool.awaitHelping(awaited, null, null, false, false, 0L);
            } catch (InterruptedException e) {
                throw new IllegalStateException("an uninterruptible wait threw", e);
            }
            return Thread.currentThread().isInterrupted();
        }, pool);
        assertTrue(get(result), "the interrupt of the waiting caller was lost");
        assertFalse(taskSawInterrupt.get(), "the task that the waiting real thread ran started with the interrupt status set");
    }

    @Test
    @DisplayName("5 thread: a task run by the waiting real thread runs on that real thread, and ownsCurrentThread() is true in it")
    void helpedTaskRunsOnTheWaitingRealThread() throws Exception {
        newSystem(1);
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            Thread waiting = Thread.currentThread();
            AtomicReference<String> seen = new AtomicReference<>();
            CompletableFuture<Integer> task = CompletableFuture.supplyAsync(() -> {
                Thread t = Thread.currentThread();
                seen.set("same=" + (t == waiting) + ", virtual=" + t.isVirtual() + ", owns=" + pool.ownsCurrentThread());
                return 1;
            }, pool);
            try {
                pool.awaitHelping(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return seen.get();
        }, pool);
        assertEquals("same=true, virtual=false, owns=true", get(result));
    }

    @Test
    @DisplayName("5 thread: a thread that is not a real thread of the pool just waits and runs nothing of the pool")
    void outsideThePoolNothingIsRun() throws Exception {
        newSystem(1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            inside.countDown();
            await(release);
        }, pool);
        await(inside);
        AtomicBoolean queuedTaskRanOnTestThread = new AtomicBoolean();
        Thread testThread = Thread.currentThread();
        CompletableFuture<Integer> queued = CompletableFuture.supplyAsync(() -> {
            queuedTaskRanOnTestThread.set(Thread.currentThread() == testThread);
            return 1;
        }, pool);
        CompletableFuture<Integer> awaited = new CompletableFuture<>();
        assertFalse(pool.awaitHelping(awaited, Duration.ofMillis(100)));
        assertFalse(queued.isDone(), "a thread outside the pool ran a task of the pool");
        release.countDown();
        get(holder);
        assertEquals(1, get(queued));
        assertFalse(queuedTaskRanOnTestThread.get());
    }
}
