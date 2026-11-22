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

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Delivery;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Fixture;

/**
 * The guarantees for {@code tell(action, pool)} and {@code ask(action, pool)} called from a real
 * thread of that same ManagedThreadPool, which then waits for the answer on that real thread (the
 * "inline" case, {@code ManagedThreadPool.ownsCurrentThread()}). Running the action at once on the
 * caller's real thread would skip the actor's turn; the message must still be queued and taken in
 * order, and the action may run on the waiting real thread only when the receive loop hands it
 * back.
 */
@Tag("PD_230128_oo01")
@DisplayName("Actor guarantees — tell and ask with a ManagedThreadPool, from a real thread of that pool")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PoolThreadCallerGuaranteesTest extends ActorGuaranteesBase {

    @Override
    List<Delivery> deliveries() {
        return List.of(ActorGuaranteeSupport.TELL_WITH_POOL_FROM_POOL_THREAD,
                ActorGuaranteeSupport.ASK_WITH_POOL_FROM_POOL_THREAD);
    }

    @Override
    String poolProblem(Fixture f) {
        return notManagedPool(f);
    }

    @Override
    String heavyThreadProblem(Fixture f, Thread loop, Thread heavy) {
        return heavyOnRealPoolThread(f, heavy);
    }
}
