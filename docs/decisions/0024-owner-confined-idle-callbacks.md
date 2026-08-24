# 0024 — Run each idle callback as one owner host turn

Status: accepted for the first Web Mobile idle-callback profile.

## Context

The selected timing profile now provides a monotonic `Performance`, allocation-bounded animation
frames, and an Android Choreographer host. Reached background work also needs the cooperative
`requestIdleCallback` surface without approximating it with a zero-delay timer or running an
unbounded callback batch between display and input work.

The request-idle-callback processing model has two ordered lists. Callbacks posted before an idle
period become runnable for that period; callbacks posted while one callback is running stay pending
until a later period. The user agent may end an idle period early and therefore need not run every
runnable callback in one period. Each callback invocation is a separate task, so Promise jobs queued
by one callback complete before another idle callback begins. An optional timeout races idle
delivery and invokes the same callback once with `didTimeout` set when the timeout task wins.

A direct-JVM implementation must preserve those observable rules while avoiding a host callback,
logical timer task, map entry, boxed handle, and queue node for every registration. It also needs to
cancel all retained work when its `RuntimeInstance` closes.

## Decision

Extend `web-timing` with:

```text
IdleCallbackHost
IdleRequestCallback
IdleDeadline
IdleCallbackExceptionReporter
IdleCallbackStore
IdleCallbackScheduler
```

`IdleCallbackHost` extends `MonotonicClock`. It accepts one reusable `IdleCallback`, requests one
future idle-period notification, and cancels that exact callback. The host supplies a monotonic
nanosecond deadline in the same time domain as `nowNanos()`. Delivery is asynchronous on the runtime
owner thread.

### One fused scheduler

One `IdleCallbackScheduler` is simultaneously:

```text
browser-facing request/cancel state
+ IdleCallbackHost.IdleCallback
+ the reusable RuntimeTask for the earliest timeout and due-timeout continuations
+ RuntimeOwnedResource while any idle work remains
```

There is no per-registration host callback, `Runnable`, `RuntimeTask`, timeout object, lifecycle
registration, or resolver. The scheduler registers itself with `RuntimeInstance` only while it owns
an active registration, pending host idle request, logical timeout, callback delivery, or admitted
timeout continuation.

Runtime shutdown cancels the exact idle-host callback and the one logical timer before clearing all
retained application callbacks. A stale platform delivery after cancellation observes the closed or
empty scheduler and performs no language work.

### Reusable registration slots

The scheduler owns one fixed `IdleCallbackStore` allocated with the scheduler. That store contains
all reusable registration and timeout metadata in parallel arrays:

```text
IdleRequestCallback[] callbacks
long[] generations
long[] timeoutDeadlineNanos
long[] timeoutSequences
int[] nextOrder
int[] previousOrder
int[] nextFreeSlot
int[] timeoutHeap
int[] timeoutHeapPositions
byte[] listKinds
boolean[] active
```

Pending and runnable callback lists use intrusive integer links. Timeout registrations share one
indexed min-heap ordered by deadline and then primitive registration sequence. Growth doubles the
arrays with `System.arraycopy`; ordinary registration, cancellation, list removal, and heap removal
do not allocate scheduler metadata.

Handles are positive integers exactly representable by a JavaScript number. Twenty low bits encode
the concurrent slot and the remaining safe-integer bits encode its generation. Reusing a slot
therefore cannot make a stale handle cancel a newer registration. A slot is retired at generation
exhaustion rather than aliasing an old handle.

### Idle-period delivery

When the host reports an idle period, the scheduler appends pending registrations to the end of the
runnable list, preserving earlier runnable work ahead of callbacks reposted during a prior turn. It
then invokes at most one callback. Ending every host notification after one callback is a deliberate
user-agent policy permitted by the processing model and gives Android or another host a chance to
observe newly runnable higher-priority work before granting another idle period.

The callback runs in one explicit `RuntimeInstance` host turn. Its `IdleDeadline` is the one required
observable allocation for that invocation. The object retains the host clock, a fixed deadline, and
the `didTimeout` flag. `timeRemaining()` samples the monotonic clock, returns milliseconds, and
clamps negative remaining time to zero.

Ordinary host deadlines are capped to 50ms from the delivery-time sample. A host may provide a
shorter deadline or one that has already expired; an expired period invokes no callback and requests
a later idle notification. A callback requested during delivery stays on the pending list and cannot
join the current period.

The runtime leaves the host turn after the single callback, producing a complete microtask and
rejection checkpoint before any later idle callback.

### Timeout race

A positive timeout inserts the registration into the scheduler's min-heap. The current runtime timer
profile can represent finite delays through `2^31 - 1` milliseconds. Larger, negative, non-finite,
or unsupported values fail explicitly; fractional values are validated and truncated, and zero does
not create a timeout race.

The scheduler arms itself through one `RuntimeInstance.setTimeout` registration for the earliest
heap deadline. Changing a non-earliest timeout does not replace that logical timer. When the alarm
fires, the scheduler rechecks the idle-host clock; an early wake is re-armed rather than treated as a
timeout.

One due registration is removed from both the order list and heap before invocation, receives an
`IdleDeadline` with zero remaining time and `didTimeout = true`, and runs in that timer host turn. If
another registration is already due, the scheduler admits itself once as the next host task. This
preserves a complete microtask checkpoint between equal-deadline timeout callbacks without creating
one task object per registration.

Idle delivery and timeout delivery race on the owner. Whichever removes the registration first wins;
the other path finds no active handle. When timeout delivery removes the last registration, the
scheduler cancels the still-pending idle-host request.

### Cancellation and failure

`cancelIdleCallback` removes a pending or runnable registration, its timeout-heap entry, and its
callback reference in O(1) plus heap repair. Invalid, fractional, stale, already-run, and foreign
handles are no-ops.

If host registration fails, the new slot, list entry, timeout entry, timer state, and any newly
acquired runtime ownership are rolled back before the original failure escapes. If exact host
cancellation fails, the language registration is already removed but the scheduler retains host
ownership until the possibly still-armed platform callback arrives as an empty no-op or runtime
shutdown retries lifecycle cleanup.

A nonfatal application callback exception is passed to `IdleCallbackExceptionReporter`; scheduler
state is already detached, so later callbacks remain runnable. A broken nonfatal reporter falls back
to the default uncaught-exception boundary. Fatal JVM errors may escape after the registration has
been removed and post-delivery scheduling has restored lifecycle state.

## Performance consequences

The first scheduler allocates one fixed store and its initial arrays. Each ordinary request then
performs one slot allocation from a primitive free list and one intrusive append. Timeout requests
additionally perform one indexed heap insertion. Cancellation is O(1) for list removal and O(log n)
only when a timeout is present.

One host idle callback serves the scheduler for its lifetime, one logical timer represents all
registered timeouts, and one scheduler object is reused for every due continuation. The only
per-delivery public allocation is the specification-visible `IdleDeadline` object.

There is no generic collection, atomic holder, weak reference, per-request `Runnable`, per-request
`RuntimeTask`, Future, coroutine, executor, Android `Handler`, polling loop, wall clock, regex,
reflection, or platform URL dependency on the selected path.

## Profile limits

- The Android idle-period adapter is a following slice. It must define how queue idleness and a
  conservative deadline are obtained without adding a periodic pump.
- The selected core invokes at most one callback per host idle notification. Hosts may grant another
  notification while the queue remains idle.
- Timeout delays above `2^31 - 1` milliseconds are refused until the runtime timer profile expands
  its exact range.
- Timer and idle-host clocks may have different origins, but must advance at the same elapsed-time
  rate. Early logical timer wakes are rechecked and re-armed.
- Visibility throttling, battery policy, input prediction, renderer deadlines, and background limits
  belong to the supplied host.
- Privacy-oriented timer precision reduction is not introduced independently in this direct native
  profile.

## Required evidence

Permanent Java 8 tests and bytecode gates prove:

- one callback burst creates one host idle request;
- one host notification invokes one callback and one complete microtask checkpoint;
- existing runnable callbacks precede callbacks reposted during a prior idle turn;
- ordinary deadlines use the monotonic host clock, cap at 50ms, and clamp remaining time to zero;
- equal timeout deadlines run FIFO as separate host turns with checkpoints between them;
- idle and timeout delivery are one-shot race winners and cancel the losing platform work;
- cancellation is generation-safe and a failed host cancellation leaves a safe stale-delivery path;
- callback exceptions are reported while later callbacks remain runnable;
- host-request failure rolls back slot, timeout, timer, and lifecycle state;
- runtime close cancels the exact idle request and one timeout alarm;
- the scheduler itself implements `IdleCallback`, `RuntimeTask`, and `RuntimeOwnedResource`;
- one fixed `IdleCallbackStore` owns parallel primitive/reference arrays and an indexed timeout heap;
- no scheduler inner wrapper class is generated; and
- no generic registry, atomic, Runnable, per-registration task, Future, coroutine, executor,
  Android scheduler, wall clock, regex, reflection, or platform parser enters production timing.
