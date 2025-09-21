package com.scivicslab.pojoactor.pojo;

/**
 * The root actor class that serves as the top-level actor in the system hierarchy.
 * This class provides basic functionality for the root actor in an actor system.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
public class Root {

    /**
     * Returns a greeting message from the root actor.
     * 
     * @return a greeting string from the root actor
     */
    public String hello() {
        return "Hello from the root actor.";
    }

}
