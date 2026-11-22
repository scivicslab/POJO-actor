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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Verifies that one scheduled task whose action never completes does not hold the scheduler's
 * real thread: other tasks keep running on schedule, and the stuck task's later ticks are skipped
 * with a WARNING naming the task.
 */
@Tag("Scheduler_260508_oo01")
@DisplayName("Scheduler — a task that never completes does not stop the others")
class SchedulerStuckTaskTest {

    private Scheduler scheduler;
    private ActorRef<StringBuilder> stuckActor;
    private ActorRef<StringBuilder> tickActor;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Logger schedulerLogger = Logger.getLogger(Scheduler.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void setUp() {
        schedulerLogger.addHandler(capture);
        scheduler = new Scheduler(1);
        stuckActor = new ActorRef<>("stuckActor", new StringBuilder());
        tickActor = new ActorRef<>("tickActor", new StringBuilder());
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        scheduler.close();
        stuckActor.close();
        tickActor.close();
        schedulerLogger.removeHandler(capture);
    }

    /** An action that holds the actor's virtual thread until the test ends. */
    private void holdForever() {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("with one real thread, a second task still runs on schedule while the first never completes")
    void scheduleAtFixedRate_oneTaskNeverCompletes_otherTaskKeepsRunning() throws Exception {
        AtomicInteger stuckRuns = new AtomicInteger();
        AtomicInteger ticks = new AtomicInteger();

        scheduler.scheduleAtFixedRate("stuck", stuckActor, sb -> {
            stuckRuns.incrementAndGet();
            holdForever();
        }, 0, 20, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("tick", tickActor, sb -> ticks.incrementAndGet(),
                10, 20, TimeUnit.MILLISECONDS);

        Thread.sleep(600);

        assertTrue(ticks.get() >= 10, "the other task should keep its schedule; ran " + ticks.get() + " times");
        assertEquals(1, stuckRuns.get(), "a tick of the stuck task must not start a second run");
        assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains("stuck")),
                "a skipped tick should be logged as a WARNING naming the task");
    }

    @Test
    @DisplayName("with fixed delay too, a task that never completes does not hold the scheduler's real thread")
    void scheduleWithFixedDelay_oneTaskNeverCompletes_otherTaskKeepsRunning() throws Exception {
        AtomicInteger ticks = new AtomicInteger();

        scheduler.scheduleWithFixedDelay("stuckDelay", stuckActor, sb -> holdForever(), 0, 20, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay("tickDelay", tickActor, sb -> ticks.incrementAndGet(),
                10, 20, TimeUnit.MILLISECONDS);

        Thread.sleep(600);

        assertTrue(ticks.get() >= 10, "the other task should keep its schedule; ran " + ticks.get() + " times");
    }
}
