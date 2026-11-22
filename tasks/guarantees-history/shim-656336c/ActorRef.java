package com.scivicslab.pojoactor.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Source compatibility for the actor-guarantee tests on 656336c, where ActorRef lives in
 * com.scivicslab.pojoactor and has no askNow. Placed in src/test/java of the checked-out old
 * version only; it adds no behaviour except askNow, which runs the old tellNow and keeps its answer.
 */
public class ActorRef<T> extends com.scivicslab.pojoactor.ActorRef<T> {

    public ActorRef(String actorName, T object, ActorSystem system) {
        super(actorName, object, system);
    }

    public <R> CompletableFuture<R> askNow(Function<T, R> action) {
        CompletableFuture<R> answer = new CompletableFuture<>();
        tellNow(target -> {
            try {
                answer.complete(action.apply(target));
            } catch (Throwable t) {
                answer.completeExceptionally(t);
            }
        });
        return answer;
    }
}
