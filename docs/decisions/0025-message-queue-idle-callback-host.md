# 0025 — Bridge one idle scheduler directly to Android MessageQueue

Status: accepted for the Android Web Mobile idle-callback adapter.

## Context

Decision 0024 places `requestIdleCallback`, cancellation handles, pending/runnable ordering,
timeout races, callback exceptions, microtask checkpoints, and runtime ownership in `web-timing`.
Android still needs a replaceable idle-period capability that can observe when the owner Looper is
about to block without approximating idleness with a repeating timer or allocating a platform object
for each Web callback.

Android exposes `MessageQueue.IdleHandler`. The queue invokes `queueIdle()` when it has no message
ready to dispatch and is about to wait. It may do so while future-dated messages are already pending.
Returning true keeps the same handler installed for a later queue-idle cycle; returning false removes
it. `Looper.myQueue()` exposes the exact current thread queue on every supported Android API level of
this profile.

The platform does not expose the due time of the next message through this baseline API. Therefore an
adapter cannot derive a browser-style idle deadline exactly. Using a long fixed budget could overlap
a near-future input, animation, or lifecycle message. Posting a Handler callback to manufacture a
second idle cycle would also turn cooperative background work into an artificial message pump.

## Decision

Add one production class to `web-timing-android`:

```text
MessageQueueIdleCallbackHost
    implements IdleCallbackHost
    implements MessageQueue.IdleHandler
```

The host is created by `forCurrentLooper()`. It captures the exact current `Looper` and its
`MessageQueue`. Construction fails explicitly when the calling thread has no Looper. Every clock,
request, cancellation, and delivery method checks exact Looper identity before touching state.

The adapter contains only:

```text
Looper looper
MessageQueue messageQueue
long idleBudgetNanos
IdleCallbackHost.IdleCallback pendingCallback
boolean insideQueueIdle
```

It creates no per-request Android callback, `Runnable`, task, token, map entry, list node, or
lifecycle wrapper.

### Monotonic conservative deadline

`nowNanos()` calls `System.nanoTime()` directly. `queueIdle()` samples the same clock once and passes
`saturatingAdd(now, idleBudgetNanos)` to the platform-independent scheduler.

The default budget is one millisecond. This is deliberately conservative because Android may invoke
an IdleHandler while a future-dated message is already waiting and the public queue API does not
expose that message's deadline. Applications with stronger queue and renderer knowledge may select a
positive custom nanosecond budget at host construction. The core still caps ordinary
`IdleDeadline.timeRemaining()` exposure to 50 milliseconds.

No wall clock, epoch conversion, `SystemClock.uptimeMillis`, Choreographer timestamp, or independently
scheduled delay participates in this adapter.

### One reusable IdleHandler

`requestIdle` stores the exact scheduler callback and normally calls:

```text
messageQueue.addIdleHandler(this)
```

If registration throws, the stored callback is cleared before the original exception escapes. A
second concurrently pending scheduler callback is rejected because one core scheduler requires only
one platform notification at a time.

`queueIdle()` removes the current callback from the field before forwarding the deadline. The core
may therefore request the next idle opportunity while processing this delivery.

When that rearm occurs inside `queueIdle()`, the adapter does not call `addIdleHandler` again. It only
stores the new scheduler callback. After the current delivery returns, `queueIdle()` returns true
when that field is non-null and false otherwise. This uses Android's existing retained-handler
contract, avoids duplicate registration while the queue is iterating its idle-handler snapshot, and
adds no platform mutation to the common rearm path.

Each invocation still reaches `IdleCallbackScheduler.onIdle` once. The core invokes at most one Web
callback in one runtime host turn and performs its full microtask/rejection checkpoint before this
Android method returns.

### Exact cancellation

`cancelIdle` compares scheduler-callback identity. A different or stale callback is a no-op.

Outside an active `queueIdle()` call, exact cancellation calls:

```text
messageQueue.removeIdleHandler(this)
```

The retained callback is cleared only after removal returns successfully. If removal throws, the
exact request remains retained so cancellation can be retried and any late platform delivery remains
safe.

During `queueIdle()`, exact cancellation clears the callback field and avoids mutating the queue while
Android is iterating it. The method then returns false and Android removes the handler. A later rearm
in the same delivery can replace the field and make the return value true again.

A stale call after successful cancellation observes no callback and returns false without entering
language state.

### No artificial idle pump

Returning true keeps the handler available for Android's next queue-idle cycle; it does not post a
message merely to force another cycle. If no later message wakes the queue, remaining untimed idle
callbacks may wait. This is an explicit mobile-host policy rather than a hidden zero-delay timer.
Callbacks that require bounded latency should use the Web timeout option, which remains implemented
by the core's one shared logical timer and preserves separate host turns.

## Performance consequences

One host allocation serves one `IdleCallbackScheduler` for its lifetime. An ordinary idle request
stores one reference and either adds the existing host once or, during rearm, changes only that
reference. A delivery samples one primitive timestamp and calls the scheduler directly. The common
multi-callback path retains the same Android IdleHandler instead of removing and re-adding it.

There is no Handler, periodic pump, collection, atomic holder, Future, coroutine, executor,
reflection, API-level branch, wall clock, or per-request `Runnable` in the selected adapter path.
Android's own MessageQueue storage remains platform implementation detail.

## Profile limits

- The default deadline budget is a conservative estimate, not a prediction of the next Android
  message or display frame.
- The application may supply a positive custom budget; renderer/input-aware budgeting requires a
  separately tested host profile.
- An untimed callback retained after one delivery runs when Android reaches a later queue-idle cycle.
  The adapter does not create work solely to wake an otherwise sleeping queue.
- Visibility, process importance, battery policy, lifecycle suspension, and background throttling
  remain application/host policy.
- The baseline uses `MessageQueue.IdleHandler` and `Looper.myQueue()` directly, with no reflective or
  version-specific alternative.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- construction fails without a Looper and records the exact current Looper;
- the default and custom positive budgets use the direct `System.nanoTime()` domain;
- the host object itself is the exact `MessageQueue.IdleHandler` registered with Android;
- one delivery clears its callback before forwarding and may rearm without a second queue add;
- `queueIdle()` return state retains or removes the one handler correctly;
- wrong and stale callbacks cannot cancel or invoke a newer request;
- add failure rolls callback state back;
- remove failure retains the exact request for retry;
- foreign-thread clock, request, and cancellation calls fail before queue mutation;
- end-to-end core delivery runs one Web callback and its final microtask checkpoint per queue-idle
  notification;
- runtime shutdown removes the exact pending IdleHandler and clears the core's shared timeout;
- the class has no inner adapter and contains direct Looper, MessageQueue, clock, and scheduler calls;
  and
- no Handler, Choreographer fallback, generic queue, atomic, Runnable, Future, coroutine, executor,
  reflection, wall-clock conversion, or periodic pump enters production idle timing.
