# 0005 — Keep Web events owner-confined and fuse AbortSignal roles

Status: accepted and implemented in `web-events`.

## Context

Fetch, WebSocket, renderer-facing objects, and other reached Web capabilities need a common event
and cancellation substrate. Copying browser-shaped method names is not sufficient: listener
mutation, capture/bubble ordering at the target, passive cancellation, one-shot listeners, abort
algorithms, dependent signals, and exception reporting are observable.

The direct-JVM runtime has one owner thread per `RuntimeInstance`. Event listeners and abort
algorithms can touch generated TypeScript state, so they cannot run on OkHttp, binder, worker-pool,
or other foreign threads. Those threads end at `PlatformPromise` or another owner-admission
boundary; event dispatch remains synchronous owner execution.

A generic Java implementation can also add avoidable allocation:

- cloning an `ArrayList` for every `dispatchEvent`;
- one wrapper object to remove each signal-bound listener;
- one `Runnable` for every `AbortSignal.timeout`;
- a separate event-handler adapter for `onabort`;
- future/coroutine cancellation semantics that do not define DOM ordering.

## Decision

`web-events` depends only on `runtime-core` and provides:

```text
Event
CustomEvent
EventTarget
AbortController
AbortSignal
DOMException
```

Every `EventTarget` is attached to one `RuntimeInstance`. Adding, removing, dispatching, reading an
abort reason, or running an abort algorithm requires an active owner host turn or microtask. The
module contains no Android, OkHttp, executor, coroutine, or transport dependency.

Listener and abort callback failures are reported through an `EventExceptionReporter` and do not
escape `dispatchEvent` or prevent later callbacks. Capability bootstraps may inject their runtime or
application error projection. The default reporter uses the owner thread's uncaught-exception hook.

## EventTarget representation

Author-created targets in this profile have no parent tree. Dispatch has two at-target invocations:

```text
capture listeners at target
then, unless propagation stopped,
non-capture listeners at target
```

A listener registration allocates one intrusive `ListenerEntry`. That same entry implements
`AbortAlgorithm` when an `AddEventListenerOptions.signal` is present, so aborting the signal removes
the registration without another wrapper.

The list is ordered by a monotonically increasing registration sequence. Each capture or bubble
invocation records a sequence cutoff. This is the allocation-free equivalent of cloning the
listener list for that invocation:

- additions never enter the invocation in which they were added;
- an addition during capture may participate in the later at-target bubble invocation;
- removals are visible immediately;
- removed entries remain tombstones while dispatch is nested and are unlinked after the outermost
  dispatch returns.

Duplicate identity is `(type, callback, capture)`. `passive`, `once`, and `signal` do not update an
existing duplicate. A `once` entry is removed before callback invocation, so recursive dispatch
cannot invoke it again. A passive listener cannot cancel the event. Listener exceptions are
reported and dispatch continues.

`Event` retains cancellation and target state after dispatch, but clears its current target, phase,
path, passive flag, and propagation flags. Dispatching the same `Event` while its dispatch flag is
set throws `InvalidStateError`.

## Abort ordering and reason representation

`AbortController.abort` is synchronous owner execution:

```text
set signal reason
mark dependent reasons
run source abort algorithms
fire source abort event
run dependent abort algorithms
fire dependent abort events
return to caller
```

A second abort is a no-op. The reason uses the same specialized value algebra as `JsPromise`:

```text
void | number | boolean | reference
```

Number and boolean reasons remain unboxed. `throwIfAborted` throws the exact reason through
`JsThrownValue`. An omitted or explicit-undefined controller reason becomes a new `AbortError`.
`AbortSignal.timeout` uses a `TimeoutError`.

Abort algorithms are an identity set stored lazily. Removal during the abort pass suppresses a
later algorithm; additions are refused after the signal becomes aborted. Non-fatal algorithm
failures are reported and do not suppress the abort event or later algorithms.

## Fused AbortSignal roles

One `AbortSignal` object can serve three additional reached roles:

```text
AbortSignal
    = EventTarget
    + timeout RuntimeTask
    + onabort EventListener
    + dependent-signal state
```

`AbortSignal.timeout` schedules the signal itself in the logical timer heap. A sub-millisecond value
that converts to zero is admitted as the same later host task; no timer or owner `Runnable` wrapper
is created. The current checked profile accepts finite delays through `2^31 - 1` milliseconds and
refuses larger values rather than routing them through the Node timer overflow clamp.

The signal also registers itself as the stable `onabort` listener. Replacing `onabort` changes the
callback field without moving that listener's position. Setting it to null removes the registration.

## Dependent signals and retention

`AbortSignal.any` flattens dependent inputs to their original source signals and preserves input
order. An already-aborted input selects the first such input's exact reason without firing a new
abort event during construction.

Source signals hold weak dependent references while a dependent has no abort listener or abort
algorithm. When the dependent gains an abort observer, the sources promote it to a strong retained
entry; removing the last observer demotes it again. This follows the DOM lifetime intent without
retaining abandoned `AbortSignal.any` results for the lifetime of a long-lived source.

When a source aborts, all dependent reasons are marked first. The source's algorithms and event then
run before dependent algorithms and events. Source links are removed after settlement so another
source cannot abort the same dependent again.

## Rejected alternatives

### Clone listeners into an array on every dispatch

Rejected as the baseline. The intrusive list plus per-invocation sequence cutoff preserves listener
mutation semantics without a dispatch-sized allocation.

### `CopyOnWriteArrayList`

Rejected. Listener mutation would copy the full list, removal flags would still be needed for an
active dispatch snapshot, and its thread-safety does not replace runtime owner confinement.

### One removal wrapper per signal-bound listener

Rejected. `ListenerEntry` already has the target and registration identity and implements the abort
algorithm directly.

### One Runnable per timeout signal

Rejected. `AbortSignal` implements `RuntimeTask` and enters the existing logical timer or host-task
queue directly.

### CompletableFuture or coroutine cancellation

Rejected as Web cancellation semantics. A capability may use internal platform machinery, but it
must terminate at owner-confined abort algorithms and `PlatformPromise`, not export future or
coroutine ordering.

### Dispatch from a worker callback

Rejected. Worker callbacks may cancel a thread-safe native operation or publish a retained result,
but they never invoke TypeScript event listeners directly.

## Consequences

- Fetch and WebSocket can share one tested EventTarget and AbortSignal foundation.
- Event dispatch and abort observation remain per-runtime and owner-confined.
- Listener dispatch allocates no snapshot collection.
- A signal-bound listener adds no second removal object.
- A timeout signal adds no timer callback wrapper.
- Primitive abort reasons remain unboxed through the Java ABI.
- Browser DOM trees, renderer propagation paths, trusted events, and HTML event-handler algorithms
  remain separate reached features rather than implied by this author-target slice.

## Required evidence

Permanent tests and structural gates must prove:

- capture listeners run before non-capture listeners at the target;
- duplicate registration is keyed by type/callback/capture;
- an addition is excluded from its current invocation but can enter a later phase;
- removal during dispatch suppresses the removed callback;
- once removal precedes recursive dispatch;
- passive listeners cannot cancel and ordinary listeners can;
- stopPropagation and stopImmediatePropagation have distinct effects;
- listener failures are reported and later listeners continue;
- recursive dispatch of the same Event reports `InvalidStateError`;
- abort algorithms precede the source abort event;
- source abort steps precede dependent abort steps;
- exact number, boolean, reference, AbortError, and TimeoutError reasons survive;
- signal-bound listeners are removed synchronously, including already-aborted signals;
- replacing `onabort` preserves its list position;
- zero active-time timeout is asynchronous and ordinary timeouts use the logical timer checkpoint;
- owner, active-turn, and cross-runtime restrictions are explicit;
- `AbortSignal` is a `RuntimeTask` and `EventListener`, never a `Runnable`;
- `ListenerEntry` is its own `AbortAlgorithm`, never a `Runnable`;
- `EventTarget` does not use `ArrayList`, `CopyOnWriteArrayList`, or a dispatch snapshot;
- no CompletableFuture, coroutine runtime, Android Handler, scheduled executor, or timer wrapper
  enters `web-events`.
