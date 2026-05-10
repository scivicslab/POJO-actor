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

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies state S1.03: ActorRef routes tell() and ask() through ManagedThreadPool.submitForActor(),
 * and clearPendingMessages() cancels both the message queue and pool jobs.
 */
@Tag("S_base.03")
@DisplayName("ActorRefIntegration — ManagedThreadPool integration (S1.03)")
public class ActorRefIntegrationTest {

    private ActorSystem system;
    private ManagedThreadPool pool;

    static class Counter {
        final AtomicInteger value = new AtomicInteger(0);
        void increment() { value.incrementAndGet(); }
        int get() { return value.get(); }
    }

    @BeforeEach
    void setUp() {
        system = new ActorSystem("test", 1); // 1 thread so jobs queue up
        pool = (ManagedThreadPool) system.getManagedThreadPool();
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    @DisplayName("tell(action, pool) routes through submitForActor() — jobs are tracked")
    void tellWithPoolTracksJobs() throws Exception {
        Counter counter = new Counter();
        ActorRef<Counter> actor = system.actorOf("counter", counter);

        CountDownLatch blocking = new CountDownLatch(1);

        // Block the single thread so subsequent jobs queue up
        actor.tell(c -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, pool);

        // Queue more jobs while thread is blocked
        actor.tell(c -> c.increment(), pool);
        actor.tell(c -> c.increment(), pool);
        actor.tell(c -> c.increment(), pool);

        Thread.sleep(100); // let jobs enter the queue

        int pending = pool.getPendingJobCountForActor("counter");
        assertTrue(pending > 0, "Jobs submitted via tell(pool) must be tracked in ManagedThreadPool");

        blocking.countDown();
    }

    @Test
    @DisplayName("ask(action, pool) routes through submitForActor() — jobs are tracked")
    void askWithPoolTracksJobs() throws Exception {
        Counter counter = new Counter();
        ActorRef<Counter> actor = system.actorOf("counter", counter);

        CountDownLatch blocking = new CountDownLatch(1);

        actor.tell(c -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, pool);

        actor.ask(c -> c.get(), pool);
        actor.ask(c -> c.get(), pool);

        Thread.sleep(100);

        int pending = pool.getPendingJobCountForActor("counter");
        assertTrue(pending > 0, "Jobs submitted via ask(pool) must be tracked in ManagedThreadPool");

        blocking.countDown();
    }

    @Test
    @DisplayName("clearPendingMessages() cancels both message queue and pool jobs")
    void clearPendingMessagesCancelsBothQueueAndPool() throws Exception {
        Counter counter = new Counter();
        ActorRef<Counter> actor = system.actorOf("counter", counter);

        CountDownLatch blocking = new CountDownLatch(1);

        actor.tell(c -> {
            try { blocking.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, pool);

        actor.tell(c -> c.increment(), pool);
        actor.tell(c -> c.increment(), pool);
        actor.tell(c -> c.increment(), pool);

        Thread.sleep(100);

        int cancelled = actor.clearPendingMessages();
        assertTrue(cancelled > 0, "clearPendingMessages() must cancel pool jobs, not just the message queue");

        blocking.countDown();
        Thread.sleep(100);

        assertEquals(0, counter.value.get(), "Cancelled jobs must not have executed");
    }

    @Test
    @DisplayName("tell(action, executor) works with any ExecutorService — job tracking is skipped")
    void tellWithGenericExecutorServiceStillWorks() throws Exception {
        Counter counter = new Counter();
        ActorRef<Counter> actor = system.actorOf("counter", counter);

        actor.tell(c -> c.increment(),
                java.util.concurrent.ForkJoinPool.commonPool())
             .get(2, TimeUnit.SECONDS);

        assertEquals(1, counter.value.get());
    }
}
