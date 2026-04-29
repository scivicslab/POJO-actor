# Scheduler — Periodic execution

A scheduler that enqueues messages to an actor on a recurring basis.
Uses `ask()` internally, so scheduled messages are serialised FIFO with regular messages.

---

## Basics

```java
Scheduler scheduler = new Scheduler();     // default 2 threads
Scheduler scheduler = new Scheduler(4);    // specify thread count

// Fixed rate (start-to-start interval)
scheduler.scheduleAtFixedRate("health", ref, a -> a.check(), 0, 10, TimeUnit.SECONDS);

// Fixed delay (end-to-start interval)
scheduler.scheduleWithFixedDelay("cleanup", ref, a -> a.cleanup(), 60, 300, TimeUnit.SECONDS);

// One-shot
scheduler.scheduleOnce("init", ref, a -> a.init(), 5, TimeUnit.SECONDS);

// Management
scheduler.cancelTask("health");
boolean active = scheduler.isScheduled("health");
int count      = scheduler.getScheduledTaskCount();

scheduler.close();  // AutoCloseable
```

---

## scheduleAtFixedRate vs scheduleWithFixedDelay

| Method | Next execution timing | If execution exceeds the period |
|--------|-----------------------|--------------------------------|
| `scheduleAtFixedRate` | `period` after the previous **start** | Fires the next run immediately without delay |
| `scheduleWithFixedDelay` | `delay` after the previous **end** | Waits the full delay before the next run |

`scheduleWithFixedDelay` is less prone to piling up when execution time is variable.
