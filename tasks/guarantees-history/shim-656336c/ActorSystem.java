package com.scivicslab.pojoactor.core;

import java.util.concurrent.ExecutorService;

/**
 * Source compatibility for the actor-guarantee tests on 656336c (see ActorRef in this directory).
 * The pool this version gives is its work-stealing pool, not a ManagedThreadPool.
 */
public class ActorSystem extends com.scivicslab.pojoactor.ActorSystem {

    public ActorSystem(String systemName) {
        super(systemName);
    }

    @Override
    public <T> ActorRef<T> actorOf(String actorName, T object) {
        ActorRef<T> actor = new ActorRef<>(actorName, object, this);
        addActor(actor);
        return actor;
    }

    public ExecutorService getManagedThreadPool() {
        return getWorkStealingPool();
    }
}
