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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pool argument of {@code tell(action, pool)} and {@code ask(action, pool)} is typed
 * {@link ManagedThreadPool}, so that a call with commonPool or any other real-thread pool does not
 * compile. A compile error cannot be asserted at run time, so the signatures are read by reflection.
 */
@DisplayName("tell/ask(action, pool) accept a ManagedThreadPool only")
class ManagedThreadPoolOnlyTest {

    private static List<Method> publicMethodsNamed(String... names) {
        List<Method> found = new ArrayList<>();
        for (Method m : ActorRef.class.getMethods()) {
            if (List.of(names).contains(m.getName()) && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == (m.getName().equals("tell")
                            ? java.util.function.Consumer.class : java.util.function.Function.class)) {
                found.add(m);
            }
        }
        return found;
    }

    @Test
    @DisplayName("the second parameter of tell(action, pool) and ask(action, pool) is ManagedThreadPool")
    void poolParameterIsManagedThreadPool() {
        List<Method> methods = publicMethodsNamed("tell", "ask");
        assertEquals(2, methods.size(), "expected exactly tell(Consumer, pool) and ask(Function, pool): " + methods);
        for (Method m : methods) {
            assertEquals(ManagedThreadPool.class, m.getParameterTypes()[1],
                    m + " must take a ManagedThreadPool, not a wider executor type");
        }
    }

    @Test
    @DisplayName("ActorSystem hands out its pool as a ManagedThreadPool, so that it can be passed")
    void systemPoolIsManagedThreadPool() throws Exception {
        assertEquals(ManagedThreadPool.class, ActorSystem.class.getMethod("getManagedThreadPool").getReturnType());
        assertEquals(ManagedThreadPool.class,
                ActorSystem.class.getMethod("getManagedThreadPool", int.class).getReturnType());
    }

    @Test
    @DisplayName("ForkJoinPoolWrapper no longer exists")
    void forkJoinPoolWrapperIsGone() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.scivicslab.pojoactor.core.ForkJoinPoolWrapper"));
    }

    @Test
    @DisplayName("tell(action, pool) still runs in the actor's turn on the given ManagedThreadPool")
    void managedPoolStillWorks() throws Exception {
        ActorSystem system = new ActorSystem("mtp-only", 1);
        try {
            ActorRef<StringBuilder> actor = system.actorOf("sb", new StringBuilder());
            ManagedThreadPool pool = system.getManagedThreadPool();
            for (int i = 0; i < 20; i++) {
                int n = i;
                actor.tell(sb -> sb.append(n).append(','), pool);
            }
            String result = actor.ask(StringBuilder::toString, pool).get(5, java.util.concurrent.TimeUnit.SECONDS);
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < 20; i++) expected.append(i).append(',');
            assertEquals(expected.toString(), result);
            assertTrue(result.startsWith("0,1,2,"));
        } finally {
            system.terminate();
        }
    }
}
