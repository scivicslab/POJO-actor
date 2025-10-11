package com.scivicslab.pojoactor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;



/**
 * A reference to an actor that provides messaging capabilities and lifecycle management.
 * This class implements the actor model pattern, allowing asynchronous message passing
 * between actors using tell() and ask() methods.
 *
 * @param <T> the type of the actor's underlying object
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
public class ActorRef<T> implements AutoCloseable {

    /** A logger for the ActorRef.
     * In order to be able to change the name of the logger later,
     * it is not made static here.
     */
    private Logger logger = Logger.getLogger(ActorSystem.class.getName());

    protected final String actorName;

    protected volatile T object;

    protected ActorSystem actorSystem = null;
    ThreadFactory factory = Thread.ofVirtual().factory();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(factory);
    
    ConcurrentSkipListSet<String> NamesOfChildren = new ConcurrentSkipListSet<>();
    String parentName;


    /**
     * Constructs an ActorRef with the specified name and object.
     * 
     * @param actorName the unique name for this actor
     * @param object the object that will handle messages for this actor
     */
    public ActorRef(String actorName, T object) {
        this.actorName = actorName;
        this.object = object;
    }

    
    /**
     * Constructs an ActorRef with the specified name, object, and actor system.
     * 
     * @param actorName the unique name for this actor
     * @param object the object that will handle messages for this actor
     * @param actorSystem the actor system this actor belongs to
     */
    public ActorRef(String actorName, T object, ActorSystem actorSystem) {
        this.actorName = actorName;
        this.object = object;
        this.actorSystem = actorSystem;
    }


    /**
     * Creates a child actor under this actor's supervision.
     * 
     * @param <K> the type of the child actor's object
     * @param actorName the unique name for the child actor
     * @param object the object for the child actor
     * @return a reference to the created child actor
     */
    public  <K> ActorRef<K> createChild(String actorName, K object) {
        ActorRef<K> child = this.actorSystem.actorOf(actorName, object);
        child.setParentName(this.actorName);
        this.NamesOfChildren.add(actorName);

        return child;
    }


    /**
     * Returns the names of all child actors supervised by this actor.
     * 
     * @return a set containing the names of child actors
     */
    public ConcurrentSkipListSet<String> getNamesOfChildren() {
        return this.NamesOfChildren;
    }

    /**
     * Returns the name of this actor's parent, if any.
     * 
     * @return the parent actor's name, or null if this actor has no parent
     */
    public String getParentName() {
        return this.parentName;
    }

    /**
     * Checks if this actor is still alive and able to process messages.
     * 
     * @return true if the actor is alive, false otherwise
     */
    public boolean isAlive() {
        return !executor.isShutdown() && !this.object.equals(null);
    }

    
    /**
     * Sets the parent name for this actor.
     * 
     * @param parentName the name of the parent actor
     */
    public void setParentName(String parentName) {
        this.parentName = parentName;
    }


    /**
     * Returns the actor system this actor belongs to.
     * 
     * @return the actor system instance
     */
    public ActorSystem system() {
        return this.actorSystem;
    }

    /**
     * Returns the name of this actor.
     * 
     * @return the actor's unique name
     */
    public String getName() {
        return this.actorName;
    }


    /**
     * Initializes the logger with the specified name.
     * 
     * @param loggerName the name for the logger
     */
    public void initLogger(String loggerName) {
        logger = Logger.getLogger(loggerName);
    }


    /**
     * Sends a message to the actor defined by this reference.
     *
     * The specified action is executed on the actor's object asynchronously in actor's thread context. This method does not wait for completion of the action, it returns immediately.
     *
     * @param action action to be executed on actor's object.
     */
    public CompletableFuture<Void> tell(Consumer<T> action) {
        T target = this.object;
        return CompletableFuture.runAsync(()->action.accept(target), executor);
    }


    /**
     * Sends a message to the actor, bypassing the mailbox and executing immediately.
     * Unlike tell(), which queues messages in the actor's single-threaded executor,
     * tellNow() executes the action immediately on a separate virtual thread, allowing it to
     * run concurrently with queued messages.
     *
     * This method is useful when:
     * - An urgent task needs to skip the queue
     * - Concurrent execution with queued messages is desired
     *
     * @param action the action to execute on this actor's object
     * @return a CompletableFuture that completes when the action finishes
     */
    public CompletableFuture<Void> tellNow(Consumer<T> action) {
        T target = this.object;
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                action.accept(target);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }


    /**
     * Sends a message to actor and returns a CompletableFuture to be completed with the response value.
     *
     * Performs the specified call on the actor's object asynchronously. The call is executed in this actor's thread context, the future is then completed with the result value in the caller's actor thread context. If the method is
     * called not from actor's context, exception is thrown.
     *
     * This method returns a CompletableFuture, which is completed with a result once the actor's call completes. If an exception occurs during actor's call, the exception is then passed to the CompletableFuture and the actor's exception handler is not triggered. Both successful and failed completions occur in the caller's actor thread context.
     *
     * @param <R> actor call response class
     * @param action action to be executed on actor's object, return value will be the response
     * @return CompletableFuture to be completed with the actor's call result
     */
    public <R> CompletableFuture<R> ask(Function<T, R> action) {
        T target = this.object;
        CompletableFuture<R> task
            = CompletableFuture.supplyAsync(()->{ return action.apply(target);}, executor);

        return task;
    }


    /**
     * Sends a message to the actor and returns a response, bypassing the mailbox and executing immediately.
     * Unlike ask(), which queues messages in the actor's single-threaded executor,
     * askNow() executes the function immediately on a separate virtual thread, allowing it to
     * run concurrently with queued messages.
     *
     * This method is useful when:
     * - You need to query the actor's state immediately without waiting for queued messages
     * - Concurrent execution with queued messages is desired
     *
     * @param <R> the type of the response
     * @param action the function to execute on this actor's object
     * @return a CompletableFuture that completes with the function's result
     */
    public <R> CompletableFuture<R> askNow(Function<T, R> action) {
        T target = this.object;
        CompletableFuture<R> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                R result = action.apply(target);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }


    /**
     * Sends a message to this actor using a specific executor service.
     * The action is executed asynchronously on the provided executor service,
     * and the result is processed in this actor's thread context.
     * 
     * @param action the action to execute on this actor's object
     * @param ws the executor service to use for executing the action
     * @return a CompletableFuture that completes when the action is processed
     */
    public CompletableFuture<Void> tell(Consumer<T> action, ExecutorService ws) {
        T target = this.object;
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> action.accept(target), ws);
        return CompletableFuture.runAsync(() -> {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.log(Level.WARNING, "Exception occurred while waiting for the result of the tell method.", e);
            }
        }, executor);
    }

    /**
     * Sends a message to this actor and returns a response using a specific executor service.
     * The action is executed asynchronously on the provided executor service,
     * and the result is processed in this actor's thread context.
     * 
     * @param <R> the type of the response
     * @param action the function to execute on this actor's object
     * @param ws the executor service to use for executing the action
     * @return a CompletableFuture containing the response from the action
     */
    public <R> CompletableFuture<R> ask(Function<T, R> action, ExecutorService ws) {
        T target = this.object;
        CompletableFuture<R> task = CompletableFuture.supplyAsync(() -> {
            return action.apply(target);
        }, ws);

        return CompletableFuture.supplyAsync(() -> {
            R result = null;
            try {
                result = task.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.log(Level.WARNING, "Exception occurred while waiting for the result of the ask method.", e);
            }
            return result;
        }, executor);
    }
    

    /**
     * Destroys the actor and cleans up its resources.
     * Pending messages will be cancelled and the actor will be removed from its system.
     * This method implements the AutoCloseable interface for resource management.
     */
    @Override
    public void close() {

        // Cancel all pending tasks and currently executing tasks
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                logger.log(Level.WARNING, "SingleThreadExecutor did not terminate");
            }
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, "InterruptedException occurred while waiting for the SingleThreadExecutor to terminate.", e);
        }
        
        this.object = null;
        
        if (this.system() != null && system().hasActor(this.actorName)) {
            system().removeActor(this.actorName);
        }
    }



    
}
