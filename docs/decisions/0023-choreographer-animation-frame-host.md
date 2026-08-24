# 0023 — Bridge one animation scheduler directly to Android Choreographer

Status: accepted for the Android Web Mobile timing adapter.

## Context

Decision 0022 places `performance.now()`, request identifiers, callback ordering, cancellation,
frame snapshots, exception reporting, microtask checkpoints, and runtime ownership in
`web-timing`. Android still needs a display-frame capability that can supply the next frame without
introducing another semantic queue or allocating one platform wrapper for every
`requestAnimationFrame` call.

Android provides one `Choreographer` per Looper thread. `postFrameCallback` schedules a one-shot
`Choreographer.FrameCallback` for the next display frame, and `removeFrameCallback` removes that
exact object. The delivered `frameTimeNanos` is a monotonic frame timestamp that can be compared with
the `System.nanoTime()` domain. Using a Handler delay, wall clock, polling timer, or independently
sampled conversion would decouple Web animation frames from display pulses and could change timing
and cancellation behavior.

A naive bridge could allocate a new `Choreographer.FrameCallback` for each request, post a Runnable
through Handler, retain several scheduler callbacks in a collection, or branch reflectively between
Android frame APIs. Those choices add allocation and duplicate state already owned by the
platform-independent scheduler.

## Decision

Add `web-timing-android` with one production class:

```text
ChoreographerAnimationFrameHost
    implements AnimationFrameHost
    implements Choreographer.FrameCallback
```

The host is constructed by `forCurrentLooper()`. It captures the exact current `Looper` and that
thread's `Choreographer`; construction fails explicitly when the thread has no Looper. Every clock,
request, cancellation, and delivery method checks exact Looper identity before touching state.

### One reusable Android callback

The host itself is the callback passed to Android. It retains one
`AnimationFrameHost.FrameCallback` field while a frame is pending:

```text
Looper looper
Choreographer choreographer
AnimationFrameHost.FrameCallback pendingCallback
```

`requestFrame` rejects a second concurrent request, stores the scheduler callback, and posts `this`
directly. If `postFrameCallback` throws, the stored callback is cleared before the failure escapes.
There is no per-request Android callback, Runnable, host task, token object, map entry, or list node.

`doFrame` clears the pending field before invoking the scheduler callback. The scheduler can
therefore request the following frame while processing the current frame, and Android receives the
same reusable host object again.

`cancelFrame` compares callback identity. A different or stale callback is a no-op. For an exact
match, it calls `removeFrameCallback(this)` and clears the field only after Android returns
successfully. If removal throws, the exact pending callback remains retained so cancellation can be
retried and a later platform delivery remains safe.

A platform delivery after successful cancellation sees no pending callback and is ignored. Normal
delivery forwards the original `frameTimeNanos` without sampling or converting another clock.

### Monotonic clock

`nowNanos()` calls `System.nanoTime()` directly on the owner Looper. This is the arbitrary monotonic
nanosecond domain used for elapsed-time comparison with Choreographer frame timestamps. The adapter
does not use `System.currentTimeMillis`, `SystemClock.uptimeMillis`, epoch conversion, or a Handler
delay.

### Android API profile

The first adapter uses `Choreographer.FrameCallback`, available from API level 16, to preserve the
project's broad Android baseline. Android 13 introduced `VsyncCallback` and richer frame timelines,
but selecting those APIs would require a separately tested profile and must not be added through
reflection or version branching in this baseline adapter.

## Performance consequences

One host allocation serves one `AnimationFrameScheduler` for its lifetime. Ordinary frames mutate
one reference field and post the same Android callback object. No allocation is added per Web frame
request or platform delivery by this adapter.

The module has no Handler, SystemClock conversion, collection, atomic, Future, coroutine, executor,
reflection, polling loop, or API-level branch. Web timing semantics stay in `web-timing`, and Android
owns only Looper attachment plus the display-frame pulse.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- construction fails without a Looper and records the exact current Looper;
- `nowNanos()` is owner-confined and calls `System.nanoTime()` without posting work;
- the host object itself is the exact `Choreographer.FrameCallback` posted to Android;
- one-shot delivery clears state early enough to re-arm from inside the callback;
- wrong and stale callbacks cannot cancel or receive a newer request;
- post failure rolls callback state back;
- removal failure retains the exact request for retry or safe delivery;
- foreign-thread request, cancellation, and clock access fail before touching Choreographer;
- end-to-end scheduler delivery produces the ordinary owner turn and microtask checkpoint;
- runtime shutdown removes an outstanding Android frame, while a delivered idle frame is not
  cancelled again; and
- bytecode contains direct Looper, Choreographer, callback, and `System.nanoTime()` calls with no
  wrapper class or forbidden scheduling dependency.
