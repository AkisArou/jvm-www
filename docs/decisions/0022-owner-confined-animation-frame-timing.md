# 0022 — Batch animation-frame callbacks in one owner turn

Status: accepted for the first Web Mobile frame-timing profile.

## Context

The selected mobile profile already has exact Promise checkpoints, logical timers, runtime-owned
transport cancellation, and an Android owner host. Renderer and animation code additionally reaches
two browser timing facilities:

- `performance.now()` as a monotonic high-resolution timestamp relative to a stable time origin; and
- `requestAnimationFrame` / `cancelAnimationFrame` as an ordered, cancellable callback batch tied to
  a display frame.

The HTML animation-frame algorithm snapshots the callback identifiers selected for a frame, removes
each identifier before invocation, allows one callback to cancel another callback that has not run,
and defers callbacks registered during the batch to a later frame. All callbacks selected for one
frame receive the same timestamp. Promise jobs queued by those callbacks do not run between callbacks;
they run at the host-task checkpoint after the frame batch.

A naive Java implementation could allocate one platform callback or `Runnable` per request, store
callbacks in a `Map<Double, Callback>`, post each callback as an independent runtime host task, poll a
clock, or use wall-clock time. Those choices add avoidable allocation and change microtask ordering.
They also leave a pending platform frame alive when its `RuntimeInstance` closes.

## Decision

Add `web-timing` with five narrow public boundaries:

```text
MonotonicClock
AnimationFrameHost
FrameRequestCallback
Performance
AnimationFrameScheduler
```

`AnimationFrameHost` extends `MonotonicClock`. It accepts one reusable `FrameCallback`, requests the
next display frame, and cancels that exact callback. Its frame timestamp uses the same arbitrary
monotonic nanosecond time base as `nowNanos()`. The host must deliver asynchronously on the runtime
owner thread.

### Performance time origin

A `Performance` object samples its origin once during construction. `now()` subtracts the origin and
returns milliseconds as a primitive `double`. The absolute host timestamp is never exposed, and no
wall clock or epoch conversion is involved.

Clock reads are owner-confined and checked for backwards movement. Double conversion is clamped to
the previous result only when rounding a very large host time base would otherwise make `now()` move
backwards. A host frame timestamp earlier than the object origin is exposed as zero. The selected
profile does not yet expose `performance.timeOrigin`, resource timing, navigation timing, marks, or
measures.

### One fused scheduler

One `AnimationFrameScheduler` is simultaneously:

```text
browser-facing request/cancel state
+ the reusable AnimationFrameHost.FrameCallback
+ one RuntimeOwnedResource while a frame is active
```

There is no per-frame host wrapper and no per-registration lifecycle object. The scheduler registers
itself with `RuntimeInstance` only while it owns a pending host frame or is executing a callback batch.
When idle it releases that primitive runtime-resource slot. Runtime shutdown cancels the exact host
frame and clears all retained callback references.

### Reusable callback slots

Registrations use parallel arrays:

```text
FrameRequestCallback[] callbacks
long[] generations
long[] frameSequences
int[] nextOrder
int[] previousOrder
int[] nextFreeSlot
boolean[] active
boolean[] cancelledRunning
```

The ordered callback list and the free list are intrusive integer links. Growth doubles the arrays
with `System.arraycopy`. A registration allocates no map entry, boxed handle, list node, or adapter.

Handles are positive integers exactly representable by a JavaScript number. Twenty low bits encode
one concurrent slot and the remaining bits encode its generation. A stale handle therefore cannot
cancel a callback in a reused slot. A slot at its maximum generation is retired rather than making an
old handle valid again.

### Frame snapshot and cancellation

The scheduler keeps separate pending and running intrusive lists. When the host frame arrives it:

1. atomically moves the pending list into the running list on the owner;
2. resets the pending list for callbacks requested during this frame;
3. enters one runtime host turn;
4. invokes every still-active running callback in FIFO order with one shared timestamp;
5. reports non-fatal callback exceptions and continues with later callbacks; and
6. leaves the host turn, producing one complete microtask/rejection checkpoint.

A callback slot is released immediately before its callback is invoked, matching removal of the
identifier before invocation. Cancellation of a later running callback clears its callback but does
not put the slot on the free list until iteration reaches it. New registration during the same frame
therefore cannot reuse a cancelled running slot and accidentally join the current snapshot.

A callback requested during the batch enters the new pending list and arms one later host frame. If
that callback is cancelled before the batch ends and it was the last pending callback, the later host
frame is cancelled directly.

### Failure boundaries

If the host rejects a frame request, the new callback registration and any newly acquired runtime
ownership are rolled back before the error escapes. A callback exception is passed to an
`AnimationFrameExceptionReporter`; a broken non-fatal reporter falls back to the default uncaught
exception boundary without corrupting the list or suppressing later callbacks. Fatal JVM errors may
escape after the scheduler releases every unvisited running slot.

## Performance consequences

The first scheduler allocates its fixed initial arrays and one `Performance` object. Every ordinary
request then performs O(1) slot allocation and intrusive-list append without allocating runtime
metadata. Cancellation is O(1) through the generation-safe handle. One platform callback serves an
entire frame, and one runtime host turn serves all callbacks selected for that frame.

There is no generic collection, atomic holder, weak reference, `Runnable`, `RuntimeTask`, Future,
coroutine, executor, Android `Handler`, polling timer, wall-clock conversion, regex, or reflection on
the selected path. The application/Android frame host remains replaceable.

## Profile limits

- The Android `Choreographer` adapter is a following transport slice.
- Background-tab throttling, display selection, variable refresh policy, and lifecycle pausing belong
  to the supplied frame host.
- `requestVideoFrameCallback`, `scheduler.postTask`, idle callbacks, performance entries, marks,
  measures, and epoch-based `timeOrigin` are not included.
- The selected handle layout supports 1,048,575 concurrent callbacks and billions of reuses per slot;
  exhaustion fails explicitly instead of aliasing a stale handle.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- `performance.now()` is relative, monotonic, primitive, and owner-confined;
- one burst of callbacks creates one host request;
- all callbacks in a frame receive one timestamp and one checkpoint follows the full FIFO batch;
- cancellation before a frame and cancellation by an earlier callback suppress the target;
- callbacks requested during a frame run only in a later frame;
- a reused slot receives a new generation and ignores stale handles;
- one callback exception is reported while later callbacks still run;
- host-request failure rolls back callback and lifecycle state;
- runtime close cancels a pending frame, while a delivered idle scheduler is not cancelled later;
- the scheduler itself implements `FrameCallback` and `RuntimeOwnedResource`;
- storage is parallel primitive/reference arrays with no per-registration class; and
- no generic queue/map, atomic, weak reference, Runnable, RuntimeTask, future, coroutine, executor,
  Android scheduler, wall clock, or `java.time` dependency enters the production timing path.
