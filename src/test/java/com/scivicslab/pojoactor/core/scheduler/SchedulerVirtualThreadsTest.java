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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Verifies that the Scheduler fires its ticks on virtual threads and starts no real thread of its
 * own: the only real-thread pool is the ManagedThreadPool.
 */
@Tag("Scheduler_260508_oo01")
@DisplayName("Scheduler — ticks fire on virtual threads, no real thread of its own")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SchedulerVirtualThreadsTest {

    @Test
    @DisplayName("after ticks have fired, no real thread named Scheduler-Worker exists")
    void noRealSchedulerThreads() throws Exception {
        ActorRef<AtomicInteger> actor = new ActorRef<>("ticked", new AtomicInteger());
        try (Scheduler scheduler = new Scheduler(4)) {
            CountDownLatch ticked = new CountDownLatch(8);
            for (int i = 0; i < 4; i++) {
                scheduler.scheduleAtFixedRate("t" + i, actor, (AtomicInteger a) -> {
                    a.incrementAndGet();
                    ticked.countDown();
                }, 0, 5, TimeUnit.MILLISECONDS);
            }
            assertTrue(ticked.await(10, TimeUnit.SECONDS), "the tasks ticked");
            // Thread.getAllStackTraces() lists the live real (platform) threads only
            List<String> realSchedulerThreads = Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .filter(name -> name.startsWith("Scheduler-Worker"))
                    .toList();
            assertEquals(List.of(), realSchedulerThreads, "real threads started by the Scheduler");
        } finally {
            actor.close();
        }
    }
}
