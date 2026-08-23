# 0003 — Bind one runtime to one Android Handler and uptime clock

Status: accepted and implemented in `runtime-android`.

## Context

The logical scheduler in `runtime-core` already owns JavaScript-visible ordering:

- owner-confined host turns;
- Promise and `queueMicrotask` checkpoints;
- foreign-event admission and wake coalescing;
- timeout/interval delay coercion, ordering, cancellation, and re-arm;
- one logical earliest timer deadline.

Android must provide the outer queue without becoming a second language scheduler. The adapter must
also use the same monotonic time base for reading time and posting an absolute Handler deadline.
Mixing `System.nanoTime`, elapsed realtime, wall time, or an independently sampled `postDelayed`
delay with Handler uptime can move a deadline or fire it early at a rounding boundary.

## Decision

`HandlerRuntimeHost` is one per-runtime host object that implements both core interfaces:

```text
HandlerRuntimeHost
    ├─ OwnerExecutor
    │    ├─ isOwnerThread -> Looper identity
    │    └─ post          -> Handler.post
    └─ TimerHost
         ├─ nowNanos      -> SystemClock.uptimeMillis × 1,000,000
         ├─ arm           -> Handler.postAtTime
         └─ disarm        -> Handler.removeCallbacks
```

A normal Android attachment is:

```java
HandlerRuntimeHost host = HandlerRuntimeHost.forCurrentLooper();
RuntimeInstance runtime = new RuntimeInstance(host, errorReporter, host);
```

The same Handler therefore receives the runtime's coalesced idle wake and its one timer alarm.

## Owner execution

`OwnerExecutor.post` may be called from a foreign thread. The adapter delegates directly to
`Handler.post`, which queues the supplied reusable runtime callback asynchronously. It never calls
the callback inline and never creates a callback wrapper.

Owner identity is `Looper.myLooper() == configuredLooper`. TimerHost methods assert that identity;
only `post` is a foreign-thread entry point. A Handler that rejects a post because its Looper is
exiting produces an explicit `IllegalStateException` so the core wake state can roll back rather
than silently losing work.

## Timer time base and rounding

`Handler.postAtTime` uses `SystemClock.uptimeMillis`. `nowNanos` therefore derives from that exact
clock, even though its resolution is one millisecond. Core timer delays are already integral
milliseconds in the current profile.

The core passes an absolute nanosecond deadline. The adapter converts it with a ceiling:

```text
handler deadline ms = ceil(deadline nanos / 1,000,000)
```

Ceiling is required because truncation could post a callback before the logical deadline. A past or
already-due deadline is still passed to `postAtTime`; Handler queues it asynchronously for the owner
rather than running it inline.

`arm` removes the previously armed callback, if any, and posts the new callback at the absolute
uptime. `disarm` removes that callback. The callback supplied by `RuntimeTimerQueue` is posted
directly, so the adapter adds no per-alarm or per-timer `Runnable` allocation.

## Lifecycle

`RuntimeInstance.close()` remains the semantic lifecycle boundary. It disarms the TimerHost,
discards queued language work, and makes a previously posted owner wake harmless. The Android host
does not own Promise state, timer entries, or callback disposal.

The application is responsible for calling `RuntimeInstance.close()` on the owner Looper before
releasing its Android component. A Looper callback that cannot be removed is allowed to arrive; the
closed runtime makes it a no-op.

## Rejected alternatives

### `Handler.postDelayed`

Rejected for the timer alarm. Converting an absolute core deadline to a relative delay requires a
second clock sample and can introduce drift or early delivery. `postAtTime` consumes the absolute
uptime deadline directly.

### `System.nanoTime`, `elapsedRealtime`, or wall time

Rejected for Handler deadlines because they are not the documented `postAtTime` clock. Wall time
also jumps. Deep sleep behavior must be the same for the clock read and the queued deadline.

### One Handler callback per logical timer

Rejected. `RuntimeTimerQueue` owns all logical timers and exposes only its reusable earliest-deadline
wake callback.

### A worker executor or scheduled executor

Rejected. It creates another threading domain and bypasses the runtime owner-turn and microtask
checkpoint boundary.

### An adapter-owned callback wrapper

Rejected. The core already supplies reusable callbacks. Posting them directly keeps the adapter
allocation-free after construction and makes structural verification simple.

## Consequences

- Android's main Looper can be the direct TypeScript owner without a private event loop.
- Foreign completion bursts still produce one coalesced Handler wake.
- Logical timers produce at most one queued Handler alarm per runtime.
- Clock and deadline scheduling share Android uptime and cannot disagree across deep sleep.
- JavaScript delay coercion, timer identity, interval behavior, Promise ordering, and rejection
  checkpoints remain absent from `runtime-android`.
- The production module imports Android only; deterministic fake `android.os` classes live in the
  separate `runtime-android-testkit` module and are never packaged with the adapter.

## Required evidence

Permanent tests and structural gates must prove:

- owner posts are asynchronous and foreign-thread safe;
- owner identity follows Looper identity;
- a missing current Looper fails explicitly;
- `nowNanos` uses `SystemClock.uptimeMillis`;
- sub-millisecond absolute deadlines round upward;
- arming replaces the previous callback and disarming removes it;
- foreign timer mutation is rejected;
- two logical timers still use one Handler alarm and preserve the microtask checkpoint between them;
- an admission burst still posts one Handler owner wake;
- runtime close removes the timer alarm and a stale owner wake runs no TypeScript;
- `HandlerRuntimeHost` implements both `OwnerExecutor` and `TimerHost`;
- no adapter wrapper class or anonymous `Runnable` exists;
- no `postDelayed`, wall clock, `System.nanoTime`, `ScheduledExecutorService`, `TimerTask`, or Java
  `Timer` enters the Android host path.
