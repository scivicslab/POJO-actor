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

import static com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.kind;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;

import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Delivery;
import com.scivicslab.pojoactor.core.guarantees.ActorGuaranteeSupport.Fixture;

/**
 * The guarantees for {@code tell} and {@code ask}: every message runs on the actor's receive loop,
 * a virtual thread, one at a time, in the order sent.
 */
@Tag("PD_230128_oo01")
@Tag("ActorMessageGuarantees_261123_oo01")
@DisplayName("Actor guarantees — tell and ask")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TellAskGuaranteesTest extends ActorGuaranteesBase {

    @Override
    List<Delivery> deliveries() {
        return List.of(ActorGuaranteeSupport.TELL, ActorGuaranteeSupport.ASK);
    }

    @Override
    String heavyThreadProblem(Fixture f, Thread loop, Thread heavy) {
        return heavy == loop && heavy.isVirtual()
                ? null
                : "the work ran on " + kind(heavy) + ", not on the receive loop's " + kind(loop);
    }
}
