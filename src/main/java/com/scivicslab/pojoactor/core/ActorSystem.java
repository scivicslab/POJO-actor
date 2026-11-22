/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.pojoactor.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * A lightweight actor system implementation that manages actor lifecycles and thread pools.
 * This system provides a framework for creating and managing actors with concurrent message processing.
 * 
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class ActorSystem {

    /*
     * This class is composed of the following categories:
     *
     * - Fields and Constructors: Includes class fields and constructors.
     * - Advanced APIs: Provides methods for specialized tasks.
     * - Primitive APIs: Offers basic operations and data processing methods.
     * - Setter/Getter methods: Provides functionality for setting and getting field values.
     */


    // =======================================================================
    // Fields and Constructors/Destructors
    // =======================================================================

    private static Logger logger = Logger.getLogger(ActorSystem.class.getName());

    protected String systemName;
    
    /**  A name to an ActorRef correspondance. */
    protected ConcurrentHashMap<String, ActorRef<?>> actors = new ConcurrentHashMap<>();


    protected List<WorkerPool> workerPools = new ArrayList<>();

    
    /**
     * Builder class for creating ActorSystem instances with custom configurations.
     */
    public static class Builder {
        String systemName = "unnamed";
        int threadNum = 1;

        /**
         * Constructs a new Builder with the specified system name.
         * 
         * @param systemName the name for the actor system
         */
        public Builder(String systemName) {
            this.systemName = systemName;
        }

        /**
         * Sets the number of real threads in the actor system's default managed thread pool.
         *
         * @param num the number of real threads in the pool
         * @return this builder instance for method chaining
         */
        public Builder threadNum(int num) {
            this.threadNum = num;
            return this;
        }

        /**
         * Builds and returns a new ActorSystem instance.
         * 
         * @return a new ActorSystem with the configured settings
         */
        public ActorSystem build() {
            ActorSystem system = new ActorSystem(systemName, threadNum);
            return system;
        }
    }



    
    /**
     * Constructs an ActorSystem with the specified name and a default managed thread pool
     * of one real thread per available processor.
     *
     * @param systemName the name of the actor system
     */
    public ActorSystem(String systemName) {
        this.systemName = systemName;
        this.workerPools.add(new ManagedThreadPool(Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Constructs an ActorSystem with the specified name and thread count.
     *
     * @param systemName the name of the actor system
     * @param threadNum the number of real threads in the default managed thread pool
     */
    public ActorSystem(String systemName, int threadNum) {
        this.systemName = systemName;
        this.workerPools.add(new ManagedThreadPool(threadNum));
    }



    
    /**
     * Terminates the actor system by closing all actors and shutting down thread pools.
     * Waits up to 60 seconds for graceful termination.
     *
     * <p>Every actor is stopped first, so that no message of any of them starts afterwards; then
     * the pools are shut down (their real threads are interrupted, and their jobs that never
     * started fail with {@link ActorClosedException}); then terminate() waits for each actor, as
     * {@link ActorRef#close()} does, and for the pools.</p>
     *
     * <p>Called from inside an action, it leaves the calling thread's interrupt flag as it was:
     * an action on an actor's mailbox virtual thread is not interrupted by its own actor's close(),
     * and an action on a pool's real thread is not left interrupted by that pool's shutdown, which
     * terminate() then does not wait for (it would wait for itself).</p>
     */
    public void terminate() {
        // A snapshot of the actors: close() takes each actor's registrations (aliases included)
        // out of the map, so looking names up again while closing would find gaps.
        List<ActorRef<?>> all = new ArrayList<>(actors.values());

        // Stop every actor before waiting for any: while terminate() waits for one actor's
        // running message, another actor's queued message must not start.
        all.forEach(ActorRef::stop);

        // Called from an action running on one of a pool's own real threads, shutdownNow()
        // interrupts that thread too, and awaitTermination() would wait for it to end, that is,
        // for itself.
        boolean wasInterrupted = Thread.currentThread().isInterrupted();
        List<WorkerPool> toAwait = new ArrayList<>();
        for (WorkerPool pool : workerPools) {
            boolean onThisPool = pool instanceof ManagedThreadPool mtp && mtp.ownsCurrentThread();
            // The tasks that never started will not run: fail their futures.
            ManagedThreadPool.failClosed(pool.shutdownNow());
            if (onThisPool) {
                if (!wasInterrupted) {
                    Thread.interrupted(); // clear the interrupt shutdownNow() gave this thread
                }
            } else {
                toAwait.add(pool);
            }
        }

        all.forEach(ActorRef::awaitStopped);

        for (WorkerPool pool : toAwait) {
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    logger.log(Level.WARNING, "Pool did not terminate");
                }
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "InterruptedException occurred while waiting for the pool to terminate.");
            }
        }
    }



    // =======================================================================
    // High level APIs
    // =======================================================================

    
    /**
     * Creates a new actor with the specified name and object.
     * 
     * @param <T> the type of the actor object
     * @param actorName the unique name for the actor
     * @param object the object that will handle messages for this actor
     * @return a reference to the created actor
     * @throws IllegalStateException when another actor is already registered under {@code actorName}
     */
    public <T> ActorRef<T> actorOf(String actorName, T object) {
        if (actors.containsKey(actorName)) throw alreadyRegistered(actorName);
        ActorRef<T> actor = new ActorRef<T>(actorName, object, this);
        if (registerOrClose(actorName, actor) != actor) {
            // Another caller registered the name after the check above.
            throw alreadyRegistered(actorName);
        }
        return actor;
    }

    /**
     * Registers an actor that was just made, if the name is free; otherwise closes it.
     *
     * <p>Making an actor starts its mailbox loop on a virtual thread, so an actor that loses the
     * name to another caller must be closed, or that virtual thread stays parked forever and keeps
     * the actor and its object reachable. This is the one place that does both: every method that
     * makes an actor for a name and then registers it -- {@link #actorOf}, and a lookup that
     * creates an actor when its name is free -- registers through here.</p>
     *
     * @param actorName the name to register under
     * @param made the actor just made for that name, not yet registered and not referred to by anyone else
     * @return {@code made} when it now holds the name; otherwise the actor that already held it,
     *         with {@code made} closed
     */
    protected ActorRef<?> registerOrClose(String actorName, ActorRef<?> made) {
        ActorRef<?> held = actors.putIfAbsent(actorName, made);
        if (held != null && held != made) {
            made.close();
            return held;
        }
        linkToThisSystem(made);
        return made;
    }

    /** Add the directly generated Actor to the ActorSystem.
     *
     * @param actor An ActorRef object to be added to the ActorSystem.
     * @return The same ActorRef object as the argument.
     * @throws IllegalStateException when another actor is already registered under its name
     */
    public <T> ActorRef<T> addActor(ActorRef<T> actor) {
        return register(actor.getName(), actor);
    }

    
    /** Add the directly generated Actor to the ActorSystem while changing the name used within the ActorSystem.
     *
     * @param actor An ActorRef object to be added to the ActorSystem.
     * @param actorName A name to be associated with the ActorRef object.
     * @return The same ActorRef object as the argument.
     * @throws IllegalStateException when another actor is already registered under {@code actorName}
     */
    public <T> ActorRef<T> addActor(ActorRef<T> actor, String actorName) {
        return register(actorName, actor);
    }

    /**
     * Registers an actor under a name, refusing a name another actor holds.
     *
     * <p>Names are one name space for the whole system. Replacing the holder of a name silently left two
     * actors believing they had it: the first one's owner went on sending to it while a lookup by the name
     * reached the second, and removing the name removed whichever was registered last. The same actor
     * registered again under its own name is no change, and is allowed. A name is free again once its actor
     * is removed.</p>
     *
     * @return the actor
     * @throws IllegalStateException when another actor is already registered under {@code actorName}
     */
    private <T> ActorRef<T> register(String actorName, ActorRef<T> actor) {
        ActorRef<?> held = actors.putIfAbsent(actorName, actor);
        if (held != null && held != actor) throw alreadyRegistered(actorName);
        linkToThisSystem(actor);
        return actor;
    }

    /**
     * An actor made with new ActorRef(name, object) has no system yet. Links it to this one, so
     * that its close() can take its registrations out of this system.
     */
    private void linkToThisSystem(ActorRef<?> actor) {
        if (actor.actorSystem == null) {
            actor.actorSystem = this;
        }
    }

    private static IllegalStateException alreadyRegistered(String actorName) {
        return new IllegalStateException("another actor is already registered as " + actorName
                + "; remove it first, or give this one a name of its own");
    }


    /**
     * Adds a new managed thread pool to the actor system.
     *
     * @param threadNum the number of real threads in the new pool
     * @return true if the pool was successfully added
     * @since 2.12.0
     */
    public boolean addManagedThreadPool(int threadNum) {
        return this.workerPools.add(new ManagedThreadPool(threadNum));
    }


    /**
     * Checks if an actor with the specified name exists in the system.
     * 
     * @param actorName the name of the actor to check
     * @return true if the actor exists, false otherwise
     */
    public boolean hasActor(String actorName) {
        return this.actors.containsKey(actorName);
    }


    /**
     * Initializes the system logger with the specified name.
     * 
     * @param loggerName the name for the logger
     */
    public void initLogger(String loggerName) {
        logger = Logger.getLogger(loggerName);
    }


    /**
     * Checks if the actor system is alive (no thread pools are shut down).
     * 
     * @return true if the system is alive, false otherwise
     */
    public boolean isAlive() {
        for (int i=0; i<workerPools.size(); i++) {
            if (workerPools.get(i).isShutdown()) {
                return false;
            }
        }
        return true;
    }


    /**
     * Checks if a specific actor is alive.
     * 
     * @param actorName the name of the actor to check
     * @return true if the actor exists and is alive, false otherwise
     */
    public boolean isAlive(String actorName) {
        if (actors.get(actorName) == null) {
            return false;
        }
        else if (actors.get(actorName).isAlive()) {
            return true;
        }
        else {
            return false;
        }
    }    
    

    /**
     * Returns a list of all actor names in the system.
     * 
     * @return a list containing the names of all actors
     */
    public List<String> listActorNames() {
        return new ArrayList<String>(actors.keySet());
    }


    
    /**
     * Removes one actor: stops its virtual thread, takes it out of this system's registry, and
     * takes its name out of its parent's {@link ActorRef#getNamesOfChildren()} (if the parent is
     * still registered). Does nothing to any children {@code actorName} itself has — see
     * {@link #removeOffsprings(String)} for that.
     *
     * <p>Removes from the registry before closing. {@link ActorRef#close()} then calls
     * {@link #unregister(ActorRef)}, which removes any other name the same actor is registered
     * under (an alias) and leaves every other actor's registration alone.
     *
     * @param actorName the name of the actor to remove
     */
    public void removeActor(String actorName) {
        ActorRef<?> actor = actors.remove(actorName);
        if (actor == null) {
            return;
        }
        forgetChildName(actor, actorName);
        if (actor.isAlive()) {
            actor.close();
        }
    }

    /**
     * Removes every registration that points to {@code actor}, under whatever name it is -- its own
     * name or an alias given to {@link #addActor(ActorRef, String)} -- and no other actor's. Called
     * by {@link ActorRef#close()}. A name another actor holds is left alone, even when it equals
     * {@code actor}'s own name.
     *
     * @param actor the actor whose registrations are to be removed
     */
    void unregister(ActorRef<?> actor) {
        for (String name : new ArrayList<>(actors.keySet())) {
            if (actors.remove(name, actor)) {
                forgetChildName(actor, name);
            }
        }
    }

    /** Takes {@code name} out of the children set of {@code actor}'s parent, if the parent is registered. */
    private void forgetChildName(ActorRef<?> actor, String name) {
        String parentName = actor.getParentName();
        if (parentName != null) {
            ActorRef<?> parent = actors.get(parentName);
            if (parent != null) {
                parent.getNamesOfChildren().remove(name);
            }
        }
    }


    /**
     * Removes every descendant of {@code actorName}, leaf before parent, leaving
     * {@code actorName} itself in place. Each descendant is removed the same way
     * {@link #removeActor(String)} removes one actor — thread stopped, taken out of this
     * system's registry, taken out of its own parent's children set.
     *
     * <p>A child name that no longer resolves to a registered {@link ActorRef} (stale
     * bookkeeping) is still dropped from {@code actorName}'s children set — {@code
     * removeActor} cannot do that for a name it never finds in the registry, so this method
     * does it directly after descending into the name.
     *
     * @param actorName the actor whose descendants (not itself) are to be removed
     */
    public void removeOffsprings(String actorName) {
        ActorRef<?> actor = actors.get(actorName);
        if (actor == null) {
            return;
        }
        for (String childName : new ArrayList<>(actor.getNamesOfChildren())) {
            removeOffsprings(childName);
            removeActor(childName);
            actor.getNamesOfChildren().remove(childName);
        }
    }

    /**
     * Removes {@code actorName} itself along with every descendant it has — the whole
     * subtree, leaf before root. Equivalent to {@link #removeOffsprings(String)} followed by
     * {@link #removeActor(String)}.
     *
     * @param actorName the root of the subtree to remove
     */
    public void removeActorRecursively(String actorName) {
        removeOffsprings(actorName);
        removeActor(actorName);
    }


    // =======================================================================
    // Primitive APIs
    // =======================================================================


    /**
     * Returns a string representation of the actor system.
     * 
     * @return a string containing the system name
     */
    public String toString() {
        return String.format("{actorSystem: \"%s\"}", systemName);
    }


    // =======================================================================
    // Setter/Getter methods
    // =======================================================================

    /**
     * Sets the logger for the actor system.
     * 
     * @param logger the logger instance to use
     */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }


    
    /**
     * Gets the current logger instance.
     * 
     * @return the current logger
     */
    public Logger getLogger() {
        return logger;
    }


    /**
     * Retrieves an actor by name.
     * 
     * @param <T> the type of the actor object
     * @param actorName the name of the actor to retrieve
     * @return the actor reference, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> ActorRef<T> getActor(String actorName) {
        return (ActorRef<T>)actors.get(actorName);
    }


    
    /**
     * Returns the default managed thread pool of the actor system.
     *
     * @return the default managed thread pool (index 0)
     * @since 2.12.0
     */
    public ExecutorService getManagedThreadPool() {
        return this.workerPools.get(0);
    }

    /**
     * Returns a managed thread pool at the specified index.
     *
     * @param n the index of the managed thread pool to return
     * @return the managed thread pool at the specified index
     * @since 2.12.0
     */
    public ExecutorService getManagedThreadPool(int n) {
        return this.workerPools.get(n);
    }
}
