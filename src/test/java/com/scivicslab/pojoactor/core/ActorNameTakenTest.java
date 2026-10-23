package com.scivicslab.pojoactor.core;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A name another actor holds is refused: two actors never both believe they have it. */
class ActorNameTakenTest {

    @Test
    void aSecondActorUnderATakenNameIsRefusedAndTheFirstKeepsIt() {
        ActorSystem system = new ActorSystem("names");
        try {
            ActorRef<StringBuilder> first = system.actorOf("worker", new StringBuilder("first"));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> system.actorOf("worker", new StringBuilder("second")));
            assertTrue(e.getMessage().contains("worker"), e.getMessage());
            assertThrows(IllegalStateException.class, () -> system.addActor(new ActorRef<>("worker", new StringBuilder("third"), system)));
            assertSame(first, system.getActor("worker"), "the first actor still holds the name");
        } finally {
            system.terminate();
        }
    }

    @Test
    void theSameActorRegisteredAgainIsNoChange() {
        ActorSystem system = new ActorSystem("names");
        try {
            ActorRef<StringBuilder> a = system.actorOf("worker", new StringBuilder());
            assertSame(a, system.addActor(a));
            assertSame(a, system.getActor("worker"));
        } finally {
            system.terminate();
        }
    }

    @Test
    void aRemovedActorsNameIsFreeAgain() {
        ActorSystem system = new ActorSystem("names");
        try {
            system.actorOf("worker", new StringBuilder("first"));
            system.removeActor("worker");
            ActorRef<StringBuilder> again = system.actorOf("worker", new StringBuilder("second"));
            assertSame(again, system.getActor("worker"));
        } finally {
            system.terminate();
        }
    }
}
