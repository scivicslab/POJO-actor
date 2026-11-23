# Changelog

## 5.0.0 (draft)

Changes since `v4.8.0` (`74f0894`). In 5.0.0 no message is left with a future that never completes: every message an actor or pool accepts gets a completed future. That future completes normally, completes exceptionally, or is cancelled. The specifications are `ActorRef_260509_oo01`, `ManagedThreadPool_260508_oo01`, `ClearPendingMessages_260509_oo01` and `Scheduler_260508_oo01`.

There is one new public class (`ActorClosedException`) and one new public method (`ManagedThreadPool.ownsCurrentThread()`). Four deprecated `ActorSystem` methods are removed (`7f04220`, below); apart from those, no public class or method was removed or renamed, and no signature changed. Code compiled against 4.8.0 that does not call the four still compiles. What changed is the behaviour below, and code that waited on futures or relied on the old behaviour has to be checked.

### An `Error` thrown by an action completes its future; only a fatal error is rethrown

Commit `a1963ab`.

Up to 4.8.0, the wrappers of `tell()` and `ask()` caught only `Exception`, both on the actor's queue and on a `ManagedThreadPool`. An `Error` such as `StackOverflowError` left the future incomplete forever. On the queue it also ended the actor's message loop without a word.

Now:

- Any `Throwable` completes the future exceptionally.
- After an ordinary `Error` (`StackOverflowError` included) the actor takes its next message.
- A fatal error is rethrown after the future is completed. A fatal error is a `VirtualMachineError` other than `StackOverflowError`, such as `OutOfMemoryError` or `InternalError`. On the actor's own message loop, the rethrow ends the loop and `isAlive()` becomes `false`. Every message still queued then completes exceptionally with an `IllegalStateException` whose `getCause()` is that error.
- The same rules apply to `tell(action, pool)` and `ask(action, pool)`.

**What 4.8 code must change:** nothing, if it already handles an exceptional future. Code that catches `ExecutionException` and assumes the cause is an `Exception` must accept an `Error` as the cause:

```java
// 4.8: the cause was always an Exception (an Error never completed the future)
catch (ExecutionException e) { Exception cause = (Exception) e.getCause(); ... }

// 5.0
catch (ExecutionException e) { Throwable cause = e.getCause(); ... }
```

### `close()` and `terminate()` fail discarded messages with `ActorClosedException`; a send after `close()` returns an already-failed future

Commit `653121b`. New class `com.scivicslab.pojoactor.core.ActorClosedException` (unchecked; `getActorName()` returns the name of the closed actor).

Up to 4.8.0, there were two cases in which a caller waited forever:

- `ActorRef.close()` cleared the actor's queue, and `ActorSystem.terminate()` dropped the jobs `shutdownNow()` returned, without completing their futures.
- A `tell` or `ask` sent after `close()` was queued where nothing would read it.

Now:

- `close()` fails the future of every message still in the actor's queue with `ActorClosedException`.
- `terminate()` fails the future of every `ManagedThreadPool` job that never started with `ActorClosedException`.
- After `close()`, `tell`, `ask`, `tellNow` and `askNow` (the pool overloads included) queue nothing and return a future that has already failed with `ActorClosedException`. If a message is queued while `close()` is running, the sender itself fails that message.
- `close()` does not touch jobs the actor submitted to a `ManagedThreadPool`. `clearPendingMessages()` cancels those (next entry).

**What 4.8 code must change:** code that joins a future across a shutdown must expect `ActorClosedException` as the cause, where it used to hang. Code that sends to an actor that may already be closed gets a failed future back instead of a future that never completes:

```java
try {
    actor.ask(Worker::result).get(10, TimeUnit.SECONDS);
} catch (ExecutionException e) {
    if (e.getCause() instanceof ActorClosedException closed) {
        // the actor closed before the message ran: closed.getActorName()
    }
}
```

### `clearPendingMessages()` and `cancelJobsForActor()` cancel, where `close()` fails

Commit `653121b`.

Up to 4.8.0, `ActorRef.clearPendingMessages()` and `ManagedThreadPool.cancelJobsForActor(String)` removed work without completing its future. Now each future of a removed message or job is cancelled with `cancel(false)`. A caller's `get()` then throws `CancellationException`.

So a discarded message tells why it was discarded:

| Who discarded the message | What its future does | What the caller's `get()` throws |
|---|---|---|
| `clearPendingMessages()`, `cancelJobsForActor()` (discarded on request) | cancelled | `CancellationException` |
| `close()`, `terminate()`, a send after `close()` (the actor or system is closing) | fails | `ExecutionException` with cause `ActorClosedException` |
| the message loop ended on a fatal error | fails | `ExecutionException` with cause `IllegalStateException` |

The count `clearPendingMessages()` returns is unchanged.

**What 4.8 code must change:** a caller that waits on a message that might be cleared must handle `CancellationException`. `CancellationException` is not an `ExecutionException`, so a `catch (ExecutionException e)` alone does not catch it.

### An interrupt no longer stops an actor

Commit `34421f8`.

Up to 4.8.0, an action that left the interrupt flag set made the next `take()` in the message loop throw `InterruptedException`. The loop then broke although the actor was still running. The actor stopped silently, and every later `tell` or `ask` waited forever. Now the loop ends only when `close()` has set `running` to `false`. Otherwise it goes on with the next message; the throw has already cleared the flag.

**What 4.8 code must change:** code that interrupted an actor's virtual thread to stop the actor must call `close()` instead.

### `close()` removes only this actor's own registrations, aliases included

Commit `34421f8`.

Up to 4.8.0, `close()` removed the system's entry under the actor's own name. An alias given with `ActorSystem.addActor(actor, alias)` stayed registered. If another actor held the same name, that other actor was removed instead.

Now:

- `close()` removes every name that maps to this actor, its aliases included, and no other actor's registration.
- `addActor()` links an actor made with `new ActorRef<>(name, object)` to the system, so that actor's `close()` also removes its registrations.
- `terminate()` closes a snapshot of the registered actors.

**What 4.8 code must change:** code that called `close()` and then looked the actor up under an alias, relying on the stale entry, now gets `null` from `getActor(alias)` and `false` from `hasActor(alias)`. Code that expected `close()` on one actor to free a name another actor also held must call `removeActor(name)` for that other actor.

### Work given to a `ManagedThreadPool` from one of its own real threads runs inline

Commit `ff3f569`. New public method `ManagedThreadPool.ownsCurrentThread()`.

Up to 4.8.0, `ask(action, pool)` and `tell(action, pool)` always queued the action on the pool. A task running on a real thread of that pool that called them and waited on the future queued the action behind itself. Once every real thread of the pool waited that way (with a 1-thread pool, at the first call), nothing was left to run the actions, and the pool deadlocked.

Now:

- `ManagedThreadPool` makes its real threads through its own thread factory.
- `ownsCurrentThread()` returns `true` only when the caller is on one of this pool's real threads.
- When it returns `true`, `ask(action, pool)` and `tell(action, pool)` run the action on the caller's real thread and return a future that is already complete. An action that throws completes that future exceptionally under the rules above.
- Called from anywhere else (a virtual thread, another pool's real thread), they queue the action on the pool as before.
- These paths never go through the actor's virtual-thread message loop, so the order of the actor's queued messages does not change.

The real threads of a `ManagedThreadPool` are now named `managed-pool-<pool number>-thread-<number>`.

**What 4.8 code must change:** nothing for callers. Code that assumed `ask(action, pool)` always returns before the action runs must not assume it when the caller is on the pool's own real thread. An action called that way runs on the calling real thread, under the caller's locks.

### `Scheduler` does not wait on its real thread; late fixed-rate ticks are skipped; fixed delay counts from the end of an execution

Commit `39039fb`.

Up to 4.8.0, each tick ran `ask(...).join()` on a real thread of the `Scheduler`. One action that never completed held that real thread, and every other schedule on the same `Scheduler` stopped with it.

Now the real thread hands the action to the actor and returns at once. A failure is logged when the future completes. Each task keeps the future of its execution in progress:

- `scheduleAtFixedRate`: a tick that comes while the previous execution of the same task has not finished is skipped, with a WARNING naming the task: `Skipping a tick of scheduled task <name>: its previous execution has not finished`. Executions of one task never overlap.
- `scheduleWithFixedDelay`: the next execution is scheduled when the current one completes, so the delay is still counted from its end.
- `scheduleOnce`: unchanged.
- A task that never completes stops only its own executions.
- The action gets no time limit. A caller that needs one puts it on the `CompletableFuture` (`TimeoutAndScheduler_251012_oo01`).

**What 4.8 code must change:** a fixed-rate task whose action can take longer than its period now runs less often than the period suggests, because the ticks it is late for are skipped. To keep a fixed number of runs, make the action shorter or use `scheduleWithFixedDelay`. Code that read a value from the actor right after a tick, assuming the `Scheduler` had already waited for the action, must instead wait on the actor itself (for example `actor.ask(...)`, which runs after the scheduled message in the actor's queue).

### Removed: the deprecated `ActorSystem` pool and registration methods

Commit `7f04220`. Code that still calls these methods no longer compiles; each has a one-line replacement. The managed pool was never a work-stealing pool: it is a `ManagedThreadPool`, a fixed pool of real threads.

- `addActor(String actorName, ActorRef<T> actor)` → `addActor(ref)`, or `addActor(ref, alias)` to register under another name.
  - Before: `system.addActor("worker", ref);`
  - After: `system.addActor(ref, "worker");` (or `system.addActor(ref);` when the name is the ref's own)
- `addWorkStealingPool(int)` → `addManagedThreadPool(int)`.
  - Before: `system.addWorkStealingPool(8);`
  - After: `system.addManagedThreadPool(8);`
- `getWorkStealingPool()` → `getManagedThreadPool()`.
  - Before: `actor.tell(a -> a.work(), system.getWorkStealingPool());`
  - After: `actor.tell(a -> a.work(), system.getManagedThreadPool());`
- `getWorkStealingPool(int)` → `getManagedThreadPool(int)`.
  - Before: `actor.tell(a -> a.work(), system.getWorkStealingPool(1));`
  - After: `actor.tell(a -> a.work(), system.getManagedThreadPool(1));`

Turing-workflow and Turing-workflow-plugins do not call these methods and need no change. Outside these repositories, `Utility-sc-account` calls `getWorkStealingPool()` 13 times, in `kakenhi/ParallelSearchEngineComparison.java` and `kakenhi/searcher/OllamaExtractorWithTimeout.java`; it stops compiling when it moves to 5.0.0.

### The jar has a module name: `com.scivicslab.pojoactor`

Commit `5c4ceca`. The jar declares `Automatic-Module-Name: com.scivicslab.pojoactor`, so it resolves on the module path under that name. Up to 4.8.0 the JVM derived a name from the jar's file name, which changed with the version.

**What 4.8 code must change:** a `module-info.java` that required the derived name requires `com.scivicslab.pojoactor`.
