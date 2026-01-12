/*
 * Copyright 2025 devteam@scivics-lab.com
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
import com.scivicslab.pojoactor.pojo.Root;


/**
 * A lightweight actor system implementation that manages actor lifecycles and thread pools.
 * This system provides a framework for creating and managing actors with concurrent message processing.
 * 
 * @author devteam@scivics-lab.com
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
         * Sets the number of threads for the actor system.
         * 
         * @param num the number of threads to use
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
     * Constructs an ActorSystem with the specified name and default thread pool.
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
     * @param threadNum the number of threads in the worker pool
     */
    public ActorSystem(String systemName, int threadNum) {
        this.systemName = systemName;
        this.workerPools.add(new ManagedThreadPool(threadNum));
    }



    
    /**
     * Terminates the actor system by closing all actors and shutting down thread pools.
     * Waits up to 60 seconds for graceful termination.
     */
    public void terminate() {
        actors.keySet().stream()
            .forEach((name)->actors.get(name).close());

        this.workerPools.stream()
            .forEach((pool)->{
                    pool.shutdownNow();
                    try {
                        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                            logger.log(Level.WARNING, "Pool did not terminate");
                        }
                    } catch (InterruptedException e) {
                        logger.log(Level.WARNING, "InterruptedException occurred while waiting for the pool to terminate.");
                    }
                });
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
     */
    public <T> ActorRef<T> actorOf(String actorName, T object) {
        ActorRef<T> actor = new ActorRef<T>(actorName, object, this);
        actors.put(actorName, actor);
        return actor;
    }

    
    /**
     * Adds an existing actor to the system with the specified name.
     * 
     * @deprecated Use {@link #addActor(ActorRef)} instead
     * @param <T> the type of the actor object
     * @param actorName the name to associate with the actor
     * @param actor the actor reference to add
     * @return the added actor reference
     */
    @Deprecated
    public <T> ActorRef<T> addActor(String actorName, ActorRef<T> actor) {
        actors.put(actorName, actor);
        return actor;
    }

    /** Add the directly generated Actor to the ActorSystem.
     *
     * @param actor An ActorRef object to be added to the ActorSystem.
     * @return The same ActorRef object as the argument.
     */
    public <T> ActorRef<T> addActor(ActorRef<T> actor) {
        String actorName = actor.getName();
        actors.put(actorName, actor);
        return actor;
    }

    
    /** Add the directly generated Actor to the ActorSystem while changing the name used within the ActorSystem.
     *
     * @param actor An ActorRef object to be added to the ActorSystem.
     * @param actorName A name to be associated with the ActorRef object.
     * @return The same ActorRef object as the argument.
     */
    public <T> ActorRef<T> addActor(ActorRef<T> actor, String actorName) {
        actors.put(actorName, actor);
        return actor;
    }


    /**
     * Adds a new managed thread pool to the actor system.
     *
     * @param threadNum the number of threads in the new pool
     * @return true if the pool was successfully added
     * @since 2.12.0
     */
    public boolean addManagedThreadPool(int threadNum) {
        return this.workerPools.add(new ManagedThreadPool(threadNum));
    }

    /**
     * Adds a new work stealing pool to the actor system.
     *
     * @param threadNum the number of threads in the new pool
     * @return true if the pool was successfully added
     * @deprecated Use {@link #addManagedThreadPool(int)} instead.
     */
    @Deprecated
    public boolean addWorkStealingPool(int threadNum) {
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
     * Removes an actor from the system.
     * 
     * @param actorName the name of the actor to remove
     */
    public void removeActor(String actorName) {
        actors.remove(actorName);
    }

    /**
     * Returns the root actor of the system.
     * 
     * @deprecated Use {@link #getActor(String)} with "ROOT" instead
     * @return the root actor reference
     */
    @Deprecated
    public ActorRef<Root> root() {
        return this.getActor("ROOT");
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

    /**
     * Returns the default work stealing pool of the actor system.
     *
     * @return the default work stealing pool (index 0)
     * @deprecated Use {@link #getManagedThreadPool()} instead.
     */
    @Deprecated
    public ExecutorService getWorkStealingPool() {
        return this.workerPools.get(0);
    }

    /**
     * Returns a work stealing pool at the specified index.
     *
     * @param n the index of the work stealing pool to return
     * @return the work stealing pool at the specified index
     * @deprecated Use {@link #getManagedThreadPool(int)} instead.
     */
    @Deprecated
    public ExecutorService getWorkStealingPool(int n) {
        return this.workerPools.get(n);
    }
}
