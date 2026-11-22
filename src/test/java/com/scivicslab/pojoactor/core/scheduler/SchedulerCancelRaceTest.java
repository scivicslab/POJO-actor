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

package com.scivicslab.pojoactor.core.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Stress test: a task cancelled or replaced while its first tick is being armed stops ticking.
 * Each repetition races two virtual threads on one task id; after every cancel has returned, a
 * task whose counter still grows is an orphan that nothing can reach.
 */
@Tag("Scheduler_260508_oo01")
@DisplayName("Scheduler — a task cancelled or replaced before its tick is set stops ticking")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SchedulerCancelRaceTest {

    private static final int REPS = 300;
    private final Logger schedulerLogger = Logger.getLogger(Scheduler.class.getName());
    private Level savedLevel;
    private Scheduler scheduler;
    private final List<ActorRef<AtomicInteger>> actors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        savedLevel = schedulerLogger.getLevel();
        schedulerLogger.setLevel(Level.SEVERE);
        scheduler = new Scheduler(4);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        actors.forEach(ActorRef::close);
        schedulerLogger.setLevel(savedLevel);
    }

    @Test
    @DisplayName("two callers schedule the same id at once, then the id is cancelled: nothing keeps ticking")
    void replaceThenCancel() throws Exception {
        List<AtomicInteger> counters = new ArrayList<>();
        for (int rep = 0; rep < REPS; rep++) {
            AtomicInteger counter = new AtomicInteger();
            ActorRef<AtomicInteger> actor = new ActorRef<>("replace" + rep, counter);
            counters.add(counter);
            actors.add(actor);
            String id = "t" + rep;
            CyclicBarrier barrier = new CyclicBarrier(2);
            Runnable schedule = () -> {
                await(barrier);
                scheduler.scheduleAtFixedRate(id, actor, AtomicInteger::incrementAndGet, 1, 1, TimeUnit.MILLISECONDS);
            };
            Thread a = Thread.ofVirtual().start(schedule);
            Thread b = Thread.ofVirtual().start(schedule);
            a.join();
            b.join();
            scheduler.cancelTask(id);
        }
        assertNoOrphans(counters);
    }

    @Test
    @DisplayName("one caller schedules, another cancels as soon as the id is registered: nothing keeps ticking")
    void cancelWhileArming() throws Exception {
        List<AtomicInteger> counters = new ArrayList<>();
        for (int rep = 0; rep < REPS; rep++) {
            AtomicInteger counter = new AtomicInteger();
            ActorRef<AtomicInteger> actor = new ActorRef<>("cancel" + rep, counter);
            counters.add(counter);
            actors.add(actor);
            String id = "t" + rep;
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread a = Thread.ofVirtual().start(() -> {
                await(barrier);
                scheduler.scheduleAtFixedRate(id, actor, AtomicInteger::incrementAndGet, 1, 1, TimeUnit.MILLISECONDS);
            });
            Thread b = Thread.ofVirtual().start(() -> {
                await(barrier);
                while (!scheduler.isScheduled(id)) {
                    Thread.onSpinWait();
                }
                scheduler.cancelTask(id);
            });
            a.join();
            b.join();
        }
        assertNoOrphans(counters);
    }

    private void assertNoOrphans(List<AtomicInteger> counters) throws InterruptedException {
        Thread.sleep(200); // let executions already handed to the actors finish
        int[] before = new int[counters.size()];
        for (int i = 0; i < before.length; i++) {
            before[i] = counters.get(i).get();
        }
        Thread.sleep(500);
        int orphans = 0;
        for (int i = 0; i < before.length; i++) {
            if (counters.get(i).get() > before[i]) {
                orphans++;
            }
        }
        assertEquals(0, orphans, "tasks still ticking after cancelTask returned: " + orphans + " of " + REPS);
        assertEquals(0, scheduler.getScheduledTaskCount());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
