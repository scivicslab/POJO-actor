package com.scivicslab.pojoactor.core;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Source compatibility for the actor-guarantee tests on 656336c (see ActorRef in this directory):
 * that version has no ManagedThreadPool, so the tests' "is the pool a ManagedThreadPool" check has
 * a class to ask about. It is never instantiated, so the check answers no.
 */
public final class ManagedThreadPool extends ThreadPoolExecutor {

    private ManagedThreadPool() {
        super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }
}
