package com.scivicslab.pojoactor;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public class TestArrayListActor {
    public static void main(String[] args) throws Exception {
        // Turn a standard ArrayList into an actor - no modifications needed!
        ActorSystem system = new ActorSystem("listSystem");
        ActorRef<ArrayList<String>> listActor = system.actorOf("myList", new ArrayList<String>());

        // Send messages to the ArrayList actor
        listActor.tell(list -> list.add("Hello"));
        listActor.tell(list -> list.add("World"));
        listActor.tell(list -> list.add("from"));
        listActor.tell(list -> list.add("POJO-actor"));

        // Query the list size
        CompletableFuture<Integer> sizeResult = listActor.ask(list -> list.size());
        System.out.println("List size: " + sizeResult.get()); // Prints: List size: 4

        // Get specific elements
        CompletableFuture<String> firstElement = listActor.ask(list -> list.get(0));
        System.out.println("First element: " + firstElement.get()); // Prints: First element: Hello

        // Even complex operations work
        CompletableFuture<String> joinedResult = listActor.ask(list -> 
            String.join(" ", list));
        System.out.println(joinedResult.get()); // Prints: Hello World from POJO-actor

        system.terminate();
    }
}
