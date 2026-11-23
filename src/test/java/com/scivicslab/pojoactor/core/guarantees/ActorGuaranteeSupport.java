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

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.ManagedThreadPool;

/**
 * What the actor-guarantee tests share: the POJO under test, a fixture that makes an actor system
 * and tells which real (platform) threads belong to its {@code ManagedThreadPool}, and the ways of
 * sending a message.
 *
 * <p>Design (PD_230128_oo01): the actor's receive loop, a virtual thread, takes one message at a
 * time. A message given a {@code ManagedThreadPool} is handed by that virtual thread to a real
 * thread of the pool, which does the CPU-bound work; the virtual thread waits for it. So every
 * message to one actor, whichever way it was sent, runs one at a time, in the order sent, and the
 * object is never touched by two threads at once. {@code tellNow} and {@code askNow} are the
 * exception by specification: they run beside the mailbox on a virtual thread of their own.</p>
 *
 * <p>This code touches no real-thread pool other than the system's {@code ManagedThreadPool}:
 * concurrent senders are virtual threads, and no commonPool, executor-less {@code *Async} or
 * parallel stream is used.</p>
 */
final class ActorGuaranteeSupport {

    private ActorGuaranteeSupport() {
    }

    /** The longest any step waits; a step that waits longer fails instead of hanging the test. */
    static final long WAIT_SECONDS = 10;

    /** How long a message that must not start is given to start anyway. */
    static final long QUIET_MILLIS = 300;

    /** How many messages the mixed-delivery tests send. */
    static final int MESSAGES = 10_000;

    /**
     * The POJO that is the actor's object. Its fields are read and written without any lock: the
     * guarantee under test is that the actor is the lock.
     */
    static final class P {
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger maxInside = new AtomicInteger();
        final AtomicLong stamps = new AtomicLong();
        volatile long heavyEndStamp;
        volatile long lightStartStamp;
        volatile Thread heavyThread;
        volatile Thread lightThread;
        final CountDownLatch heavyStarted = new CountDownLatch(1);
        final CountDownLatch heavyRelease = new CountDownLatch(1);
        /** Read and written with no lock and no volatile: a lost update shows as a short count. */
        int counter;
        final AtomicInteger runs = new AtomicInteger();
        final Queue<Integer> startOrder = new ConcurrentLinkedQueue<>();

        private void enter() {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
        }

        private void exit() {
            inside.decrementAndGet();
        }

        /** Widens the window inside a method so that two threads inside it overlap with near certainty. */
        private static void pause() {
            for (int i = 0; i < 300; i++) {
                Thread.onSpinWait();
            }
        }

        /** A held-up message: it starts, then waits until the test releases it, then ends. */
        void heavy() {
            enter();
            try {
                heavyThread = Thread.currentThread();
                heavyStarted.countDown();
                try {
                    heavyRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                heavyEndStamp = stamps.incrementAndGet();
            } finally {
                exit();
            }
        }

        /** A quick message that records when it started and on which thread. */
        void light() {
            enter();
            try {
                lightThread = Thread.currentThread();
                lightStartStamp = stamps.incrementAndGet();
            } finally {
                exit();
            }
        }

        /** Does nothing but be inside the object for a moment. */
        void touch() {
            enter();
            try {
                pause();
            } finally {
                exit();
            }
        }

        /** Adds one to the unguarded counter, with a pause between the read and the write. */
        void increment() {
            enter();
            try {
                int read = counter;
                pause();
                counter = read + 1;
            } finally {
                exit();
            }
        }

        /** Counts a run with an atomic, so that it is right whatever the threads do. */
        void count() {
            enter();
            try {
                runs.incrementAndGet();
            } finally {
                exit();
            }
        }

        /** Notes that message {@code i} started. */
        void record(int i) {
            enter();
            try {
                startOrder.add(i);
            } finally {
                exit();
            }
        }
    }

    /** An actor system with one actor {@code p}, and the real threads of its pool, identified. */
    static final class Fixture implements AutoCloseable {
        /** The pool has {@code availableProcessors()} real threads; -XX:ActiveProcessorCount changes that. */
        final ActorSystem system = new ActorSystem("actor-guarantees");
        final ManagedThreadPool pool = system.getManagedThreadPool();
        /** The real (platform) threads of {@link #pool}. */
        final Set<Thread> poolThreads = ConcurrentHashMap.newKeySet();
        final P p = new P();
        final ActorRef<P> actor = system.actorOf("p", p);

        Fixture() throws InterruptedException {
            int n = Runtime.getRuntime().availableProcessors();
            CyclicBarrier together = new CyclicBarrier(n);
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                // n tasks that wait for each other occupy n different real threads of the pool
                pool.execute(() -> {
                    poolThreads.add(Thread.currentThread());
                    try {
                        together.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // the size check below fails the test
                    }
                    done.countDown();
                });
            }
            if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS) || poolThreads.size() != n) {
                throw new AssertionError("could not identify the " + n + " real threads of the pool, found "
                        + poolThreads.size());
            }
        }

        @Override
        public void close() {
            p.heavyRelease.countDown(); // a held-up message must not outlive the test
            system.terminate();
        }
    }

    /** What a delivery returns: when the send call came back, and when the work and its wait ended. */
    record Sent(CountDownLatch called, CompletableFuture<?> done) {
        static Sent returned(CompletableFuture<?> done) {
            return new Sent(new CountDownLatch(0), done);
        }

        void awaitCall() throws InterruptedException {
            if (!called.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("the send call did not return within " + WAIT_SECONDS + " s");
            }
        }
    }

    /** One way of sending a message to the actor. */
    interface Delivery {
        String name();

        /**
         * Sends {@code work}. A delivery that sends from a real thread of the pool does so on a
         * task of the pool and returns at once; {@link Sent#awaitCall()} waits for the send call.
         */
        Sent deliver(Fixture f, Consumer<P> work);
    }

    static Function<P, Object> asFunction(Consumer<P> work) {
        return p -> {
            work.accept(p);
            return null;
        };
    }

    static final Delivery TELL = new Delivery() {
        public String name() {
            return "tell";
        }

        public Sent deliver(Fixture f, Consumer<P> work) {
            return Sent.returned(f.actor.tell(work));
        }
    };

    static final Delivery ASK = new Delivery() {
        public String name() {
            return "ask";
        }

        public Sent deliver(Fixture f, Consumer<P> work) {
            return Sent.returned(f.actor.ask(asFunction(work)));
        }
    };

    static final Delivery TELL_WITH_POOL = new Delivery() {
        public String name() {
            return "tell(action, pool)";
        }

        public Sent deliver(Fixture f, Consumer<P> work) {
            return Sent.returned(f.actor.tell(work, f.pool));
        }
    };

    static final Delivery ASK_WITH_POOL = new Delivery() {
        public String name() {
            return "ask(action, pool)";
        }

        public Sent deliver(Fixture f, Consumer<P> work) {
            return Sent.returned(f.actor.ask(asFunction(work), f.pool));
        }
    };

    static final Delivery TELL_NOW = new Delivery() {
        public String name() {
            return "tellNow";
        }

        public Sent deliver(Fixture f, Consumer<P> work) {
            return Sent.returned(f.actor.tellNow(work));
        }
    };

    static final Delivery ASK_NOW = new Delivery() {
        public String name() {
            return "askNow";
        }

        public Sent deliver(Fixture f, Consumer<P> work) {
            return Sent.returned(f.actor.askNow(asFunction(work)));
        }
    };

    /**
     * A send made from a real thread of the pool, then a wait on that same real thread: the
     * "inline" case, where {@code ManagedThreadPool.ownsCurrentThread()} is true for the caller.
     * The action must still run in the actor's turn.
     */
    private static Delivery fromPoolThread(String name, boolean ask) {
        return new Delivery() {
            public String name() {
                return name;
            }

            public Sent deliver(Fixture f, Consumer<P> work) {
                CountDownLatch called = new CountDownLatch(1);
                CompletableFuture<Void> done = new CompletableFuture<>();
                f.pool.execute(() -> {
                    try {
                        if (!f.poolThreads.contains(Thread.currentThread())) {
                            throw new AssertionError("the sender is not a real thread of the pool");
                        }
                        CompletableFuture<?> sent = ask
                                ? f.actor.ask(asFunction(work), f.pool)
                                : f.actor.tell(work, f.pool);
                        called.countDown();
                        sent.get(WAIT_SECONDS, TimeUnit.SECONDS); // waits on this real thread of the pool
                        done.complete(null);
                    } catch (Throwable t) {
                        called.countDown();
                        done.completeExceptionally(t);
                    }
                });
                return new Sent(called, done);
            }
        };
    }

    static final Delivery TELL_WITH_POOL_FROM_POOL_THREAD = fromPoolThread("tell(action, pool) from a real thread of the pool", false);

    static final Delivery ASK_WITH_POOL_FROM_POOL_THREAD = fromPoolThread("ask(action, pool) from a real thread of the pool", true);

    // ------------------------------------------------------------------ waiting and reporting

    static void await(CompletableFuture<?> future, String what) throws InterruptedException, ExecutionException {
        try {
            future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(what + " did not finish within " + WAIT_SECONDS + " s");
        }
    }

    static void await(CountDownLatch latch, String what) throws InterruptedException {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError(what + " did not happen within " + WAIT_SECONDS + " s");
        }
    }

    /** Whether {@code t} is a real thread of Java's common pool. */
    static boolean onCommonPool(Thread t) {
        return t instanceof ForkJoinWorkerThread w && w.getPool() == ForkJoinPool.commonPool();
    }

    /** A thread, with whether it is a real (platform) thread or a virtual thread. */
    static String kind(Thread t) {
        return t == null ? "no thread" : (t.isVirtual() ? "virtual thread " : "real thread ") + t.getName();
    }

    /** Where in the guarantee tests a failure happened, as File.java:line. */
    static String where(Throwable t) {
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith("com.scivicslab.pojoactor.core.guarantees.")) {
                return e.getFileName() + ":" + e.getLineNumber();
            }
        }
        return "?";
    }

    /** One line for a failure of one delivery, naming it and the line that failed. */
    static String describe(Delivery d, Throwable t) {
        Throwable cause = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
        return d.name() + " [" + where(cause) + "]: " + cause;
    }
}
