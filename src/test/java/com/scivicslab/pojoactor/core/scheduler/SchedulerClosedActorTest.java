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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Verifies that a scheduled task whose actor is closed stops, with one WARNING naming it, instead
 * of firing and logging SEVERE at every tick; and that a task whose action fails for another
 * reason keeps its schedule.
 */
@Tag("Scheduler_260508_oo01")
@DisplayName("Scheduler — a task whose actor is closed stops; other failures keep the schedule")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SchedulerClosedActorTest {

    private final Logger schedulerLogger = Logger.getLogger(Scheduler.class.getName());
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private boolean savedUseParent;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        savedUseParent = schedulerLogger.getUseParentHandlers();
        schedulerLogger.setUseParentHandlers(false);
        schedulerLogger.addHandler(capture);
        scheduler = new Scheduler(2);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        schedulerLogger.removeHandler(capture);
        schedulerLogger.setUseParentHandlers(savedUseParent);
    }

    @Test
    @DisplayName("fixed-rate and fixed-delay tasks of a closed actor stop and are logged once each")
    void tasksOfClosedActorStop() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        ActorRef<AtomicInteger> actor = new ActorRef<>("closing", counter);
        scheduler.scheduleWithFixedDelay("delay", actor, AtomicInteger::incrementAndGet, 0, 10, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate("rate", actor, AtomicInteger::incrementAndGet, 0, 10, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        assertTrue(counter.get() > 0, "the tasks ran before the actor was closed");

        records.clear(); // before close(): a tick during close() already logs its task as stopped
        actor.close();
        Thread.sleep(1000);

        long severe = records.stream().filter(r -> r.getLevel() == Level.SEVERE).count();
        long stopped = records.stream()
                .filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("is closed")).count();
        assertEquals(0, severe, "SEVERE logs in the second after the actor was closed: " + severe);
        assertEquals(2, stopped, "one WARNING per stopped task");
        assertFalse(scheduler.isScheduled("delay"));
        assertFalse(scheduler.isScheduled("rate"));
        assertEquals(0, scheduler.getScheduledTaskCount());
    }

    @Test
    @DisplayName("a fixed-delay task whose action throws keeps running")
    void otherFailuresKeepTheSchedule() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ActorRef<AtomicInteger> actor = new ActorRef<>("failing", attempts);
        try {
            scheduler.scheduleWithFixedDelay("failing", actor, (AtomicInteger a) -> {
                a.incrementAndGet();
                throw new IllegalStateException("planned failure");
            }, 0, 10, TimeUnit.MILLISECONDS);
            Thread.sleep(300);
            assertTrue(attempts.get() >= 3, "attempts: " + attempts.get());
            assertTrue(scheduler.isScheduled("failing"));
        } finally {
            actor.close();
        }
    }
}
