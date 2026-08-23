# 0002 — Keep logical timers in each runtime and arm one host deadline

Status: accepted and implemented for `setTimeout`, `clearTimeout`, `setInterval`, and `clearInterval`; the Android `Handler` adapter remains a later slice.

## Context

A direct-JVM application still needs JavaScript timer behavior, but Android already owns the outer event loop. A naive implementation can easily add the wrong scheduler:

- one `Handler`/`Runnable` per logical timer;
- a periodic polling callback that wakes an idle application;
- `ScheduledExecutorService` callbacks on worker threads;
- wall-clock deadlines that jump when system time changes;
- fixed-rate intervals that overlap or catch up after a slow callback;
- timer callbacks that bypass the runtime host-turn boundary and therefore skip Promise microtask checkpoints.

Those approaches either add avoidable work or change observable JavaScript ordering. The existing ScriptC runtime already uses a deadline/sequence min-heap, re-arms intervals from callback completion, and performs a microtask checkpoint between host tasks. The direct-JVM path preserves that contract while exploiting Android's ability to wake the owner Looper at one absolute deadline.

## Decision

Each `RuntimeInstance` lazily owns one logical timer queue. The queue contains JavaScript timer state; a replaceable `TimerHost` contains only platform clock and alarm mechanics.

```text
RuntimeInstance
    └─ RuntimeTimerQueue
         ├─ deadline/sequence min-heap
         ├─ generation-checked shared handle slots
         ├─ one reusable wake Runnable
         └─ TimerHost
              ├─ monotonic now
              ├─ arm one absolute deadline
              └─ disarm
```

The static runtime ABI is:

```java
double setTimeout(RuntimeTask callback, double delayMilliseconds);
double setInterval(RuntimeTask callback, double delayMilliseconds);
void clearTimeout(double handle);
void clearInterval(double handle);
```

Timeouts and intervals deliberately share one handle map. Either clear function cancels either timer kind, matching the observable Web timer model instead of inventing Java type identity for numeric handles.

A callback is already a compiler/runtime job. The runtime does not wrap every timer in another `Runnable`. The one `Runnable` visible to the platform is the reusable alarm callback owned by the timer queue.

## TimerHost contract

`TimerHost` is invoked only on the runtime owner thread. It must:

- return a non-negative monotonic timestamp in nanoseconds;
- replace any previous alarm when `arm(deadline, callback)` is called;
- invoke the supplied callback asynchronously on that same owner thread;
- never invoke the callback inline from `arm`;
- cancel the current alarm when `disarm()` is called.

The logical runtime uses absolute monotonic nanoseconds. An Android adapter should use the same time base as its `Handler` scheduling primitive, normally uptime, and round an absolute deadline so it does not fire early. Android does not decide delay coercion, timer ordering, handle identity, or interval behavior.

A runtime constructed without a timer capability uses `TimerHost.UNSUPPORTED`; reaching either timer-registration function then fails explicitly instead of inventing a private scheduler.

## Delay coercion

The current checked ScriptC timer surface has Node-compatible delay coercion, and the JVM tier preserves it exactly:

```text
NaN, negative, zero, sub-1ms  -> 1 ms
+Infinity or > 2^31 - 1       -> 1 ms
finite value in range         -> truncate toward zero to integer ms
```

Thus `1.1` and `1.8` share the 1 ms bucket and retain registration order.

This is not a claim that Node rules are universally correct for every future Web Mobile profile. HTML nested-timer clamping is a separate compatibility decision. A future profile may select it explicitly, but no target adapter may silently mix the two rule sets.

## Ordering and checkpoints

Heap order is:

1. absolute deadline;
2. monotonically increasing registration sequence.

When the host alarm fires, the runtime removes one due timer and executes its callback as one ordinary host task:

```text
host alarm callback
    -> timer callback enters host turn
    -> generated TypeScript runs
    -> outer timer turn exits
    -> microtasks drain to exhaustion
    -> rejection checkpoint
    -> interval may re-arm
    -> next due timer may run
```

Therefore a microtask queued by timer A runs before timer B, even when both had the same deadline. The same checkpoint is part of interval cancellation: an interval remains addressable until its callback and all resulting microtasks finish.

A timer alarm may execute only a bounded number of due callbacks before returning to the Looper. If more due timers remain, the runtime re-arms the same host callback at the already-due earliest deadline. The fairness budget never truncates a microtask checkpoint.

Timers registered or cancelled from inside a firing callback mutate the logical heap immediately, but alarm synchronization is deferred until the current timer wake finishes. This avoids repeated `Handler` re-arming during one owner turn.

## Interval rule

Intervals are completion-based and non-overlapping. After a callback and its complete microtask/rejection checkpoint, an interval that has not been cleared receives:

```text
next deadline = current monotonic time + original coerced delay
next sequence = fresh registration sequence
```

This is not fixed-rate scheduling. A slow callback does not cause overlapping deliveries or a burst of catch-up ticks. Re-entering with a fresh sequence also makes the next tick a new FIFO registration relative to timers scheduled for the same deadline.

`clearInterval` or `clearTimeout` may cancel the interval from:

- the interval callback itself;
- a microtask queued by that callback;
- another owner turn before the next deadline.

A firing interval is not placed on the reusable-slot free list until the checkpoint returns. Reentrant timer registration therefore cannot overwrite metadata still needed to decide whether the interval re-arms.

A non-fatal callback failure is reported through the ordinary `HOST_TASK` error boundary. It does not silently cancel the interval; the interval continues unless user code cleared it.

## One alarm, no polling

The timer queue tracks the currently armed earliest deadline.

- Adding a later timer does not touch the host alarm.
- Adding an earlier timer replaces the alarm once.
- Cancelling a non-earliest timer does not touch the alarm.
- Cancelling the earliest timer arms the next deadline once.
- Removing the final timer disarms the host.
- An idle runtime with no timers performs no periodic work.

A stale host callback that arrives after runtime shutdown observes the closed runtime and becomes a no-op.

## Handles and cancellation

Timer handles are positive integers exactly representable by a JavaScript `number` (at most `2^53 - 1`). The encoding uses:

```text
20 low bits  -> concurrent slot index + 1
33 high bits -> slot generation
```

A slot is reused only after its generation advances. Consequently an old handle cannot cancel a later timer occupying the same slot. A slot whose generation space is exhausted is retired rather than making a stale handle valid again.

Cancellation is eager when the registration is not currently firing:

- invalid, fractional, stale, already-fired, and already-cleared handles are no-ops;
- the heap entry is removed immediately;
- the callback reference is released immediately;
- `RuntimeTask.discard()` runs so retained native/transport resources can be released;
- cancelling the earliest deadline updates the one host alarm.

For a firing interval, cancellation is recorded immediately but callback disposal is deferred until the full checkpoint returns. `RuntimeTask.discard()` may therefore run after earlier successful interval deliveries; it means that the repeating registration is retired, not that its callback never executed.

## Allocation policy

The queue is allocated lazily on first timer use. It keeps:

- one heap array;
- one slot array;
- one reusable host wake `Runnable`;
- one reusable `TimerEntry` object per concurrent-slot high-water mark.

After a slot has been allocated once, later timer registrations can reuse that entry without allocating another timer node. The callback/closure itself is generated language state and is not duplicated by the timer runtime. A firing interval temporarily retains its slot to protect reentrant correctness, then returns it to the same free list when cancelled.

## Shutdown

`RuntimeInstance.close()` disarms the host alarm, removes every active timeout and interval, and calls `discard()` on every queued callback. A host alarm already posted before shutdown may still arrive, but it cannot execute TypeScript after the runtime closes.

## Rejected alternatives

### `ScheduledExecutorService`

Rejected as the language timer scheduler. It introduces another executor/threading domain, does not establish the runtime's owner turn, and does not provide the required Promise checkpoint between callbacks.

### `scheduleAtFixedRate` for intervals

Rejected. It has the wrong non-overlap and catch-up behavior and would move interval identity into a foreign scheduler.

### One Android Runnable per timer

Rejected. It creates platform objects in proportion to logical timers, fragments ordering across the Looper queue, and makes equal-deadline registration order and cancellation a platform accident.

### Periodic timer pump

Rejected. It wakes idle applications, adds latency bounded by the polling period, and performs work even when no timer exists.

### Wall-clock time

Rejected for deadlines. Clock changes must not move a pending timeout backward or forward.

### Execute all due timers as one host task

Rejected because microtasks created by the first callback would run only after every due timer, violating the required task/microtask ordering.

## Consequences

- Timer semantics remain per-runtime and owner-confined.
- Android receives at most one armed timer callback per runtime.
- Timeouts and intervals share ordering, handles, cancellation, and host-turn boundaries.
- Intervals never overlap and can be cancelled through their callback checkpoint.
- Cancellation is eager and stale-handle safe.
- No JNI transition, worker pool, or periodic pump is introduced.
- Refresh/ref/unref, trailing-argument compiler lowering, and Android packaging remain explicit later work.

## Required evidence

Permanent tests must prove:

- exact clamp-and-truncate delay coercion;
- equal-deadline registration order;
- a microtask checkpoint between due callbacks;
- cancellation prevents delivery and calls `discard()` once;
- stale generations cannot cancel a reused slot;
- only earliest-deadline changes re-arm the host;
- a fairness budget returns to the host without reordering timers;
- a callback can register another timer without inline delivery;
- callback failure does not strand later timers;
- intervals re-arm from callback completion rather than fixed rate;
- an interval can clear itself from its callback;
- a callback microtask can clear the interval before re-arm;
- either clear function cancels either timer kind;
- a non-fatal interval callback failure does not cancel the interval;
- interval and timeout callbacks share one deadline/FIFO queue;
- a stale interval handle cannot cancel a reused timeout slot;
- shutdown disarms and discards every timer kind;
- owner/active-turn checks reject foreign timer mutation;
- `TimerEntry` is not a `Runnable`;
- no `ScheduledExecutorService`, `TimerTask`, or periodic pump enters `runtime-core`.
