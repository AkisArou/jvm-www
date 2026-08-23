# 0002 — Keep logical timers in each runtime and arm one host deadline

Status: accepted and implemented for one-shot `setTimeout` / `clearTimeout`; intervals and the Android `Handler` adapter remain later slices.

## Context

A direct-JVM application still needs JavaScript timer behavior, but Android already owns the outer event loop. A naive implementation can easily add the wrong scheduler:

- one `Handler`/`Runnable` per logical timer;
- a periodic polling callback that wakes an idle application;
- `ScheduledExecutorService` callbacks on worker threads;
- wall-clock deadlines that jump when system time changes;
- timer callbacks that bypass the runtime host-turn boundary and therefore skip Promise microtask checkpoints.

Those approaches either add avoidable work or change observable JavaScript ordering. The existing ScriptC runtime already uses a deadline/sequence min-heap and performs a microtask checkpoint between host tasks. The direct-JVM path must preserve that contract while exploiting Android's ability to wake the owner Looper at one absolute deadline.

## Decision

Each `RuntimeInstance` lazily owns one logical timer queue. The queue contains JavaScript timer state; a replaceable `TimerHost` contains only platform clock and alarm mechanics.

```text
RuntimeInstance
    └─ RuntimeTimerQueue
         ├─ deadline/sequence min-heap
         ├─ generation-checked handle slots
         ├─ one reusable wake Runnable
         └─ TimerHost
              ├─ monotonic now
              ├─ arm one absolute deadline
              └─ disarm
```

The runtime exposes the first static timer ABI as:

```java
double setTimeout(RuntimeTask callback, double delayMilliseconds);
void clearTimeout(double handle);
```

A callback is already a compiler/runtime job. The runtime does not wrap every timer in another `Runnable`. The one `Runnable` visible to the platform is the reusable alarm callback owned by the timer queue.

## TimerHost contract

`TimerHost` is invoked only on the runtime owner thread. It must:

- return a non-negative monotonic timestamp in nanoseconds;
- replace any previous alarm when `arm(deadline, callback)` is called;
- invoke the supplied callback asynchronously on that same owner thread;
- never invoke the callback inline from `arm`;
- cancel the current alarm when `disarm()` is called.

The logical runtime uses absolute monotonic nanoseconds. An Android adapter should use the same time base as its `Handler` scheduling primitive, normally uptime, and round an absolute deadline so it does not fire early. Android does not decide delay coercion, timer ordering, handle identity, or interval behavior.

A runtime constructed without a timer capability uses `TimerHost.UNSUPPORTED`; reaching `setTimeout` then fails explicitly instead of inventing a private scheduler.

## Delay coercion

The current checked ScriptC timer surface has Node-compatible delay coercion, and the JVM tier preserves it exactly for this slice:

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
    -> next due timer may run
```

Therefore a microtask queued by timer A runs before timer B, even when both had the same deadline.

A timer alarm may execute only a bounded number of due callbacks before returning to the Looper. If more due timers remain, the runtime re-arms the same host callback at the already-due earliest deadline. The fairness budget never truncates a microtask checkpoint.

Timers registered or cancelled from inside a firing callback mutate the logical heap immediately, but alarm synchronization is deferred until the current timer wake finishes. This avoids repeated `Handler` re-arming during one owner turn.

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

`clearTimeout` is an eager cancellation:

- invalid, fractional, stale, already-fired, and already-cleared handles are no-ops;
- the heap entry is removed immediately;
- the callback reference is released immediately;
- `RuntimeTask.discard()` runs so retained native/transport resources can be released;
- cancelling the earliest deadline updates the one host alarm.

The same handle space is intended to be shared with intervals in the later interval slice.

## Allocation policy

The queue is allocated lazily on first timer use. It keeps:

- one heap array;
- one slot array;
- one reusable host wake `Runnable`;
- one reusable `TimerEntry` object per concurrent-slot high-water mark.

After a slot has been allocated once, later timer registrations can reuse that entry without allocating another timer node. The callback/closure itself is generated language state and is not duplicated by the timer runtime.

## Shutdown

`RuntimeInstance.close()` disarms the host alarm, removes every active timer, and calls `discard()` on every queued callback. A host alarm already posted before shutdown may still arrive, but it cannot execute TypeScript after the runtime closes.

## Rejected alternatives

### `ScheduledExecutorService`

Rejected as the language timer scheduler. It introduces another executor/threading domain, does not establish the runtime's owner turn, and does not provide the required Promise checkpoint between callbacks.

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
- Timer callbacks and platform completions share the same host-turn/microtask boundary.
- Cancellation is eager and stale-handle safe.
- No JNI transition, worker pool, or periodic pump is introduced.
- Intervals, refresh/ref/unref, trailing-argument lowering, and Android packaging remain explicit later work.

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
- shutdown disarms and discards every timer;
- owner/active-turn checks reject foreign timer mutation;
- `TimerEntry` is not a `Runnable`;
- no `ScheduledExecutorService`, `TimerTask`, or periodic pump enters `runtime-core`.
