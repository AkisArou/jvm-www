# 0004 — Fuse the platform Promise, completion token, and admitted task

Status: accepted and implemented in `runtime-core`.

## Context

Android and JVM capabilities such as Fetch, WebSocket, filesystem operations, sensors, and binding-generated callbacks complete outside the TypeScript owner turn. Their callbacks may arrive on OkHttp, binder, pool, or library-owned threads.

The language Promise cannot be settled on those threads. `JsPromise` state, reaction lists, rejection tracking, and generated TypeScript heap objects are owner-confined. A platform callback must publish a transport-safe result, admit one host event, and let the runtime owner perform settlement.

A generic JVM design commonly allocates several objects per operation:

```text
returned Promise
+ resolver/completer
+ Future/coroutine object
+ completion payload wrapper
+ Runnable submitted to the owner
```

That erases checked payload types, adds scheduling abstractions whose ordering is not ECMAScript ordering, and retains more objects than the operation requires.

## Decision

One `PlatformPromise` object represents the common platform-backed operation and is simultaneously:

```text
returned JsPromise
+ foreign first-completion token
+ transport-safe captured payload
+ admitted RuntimeTask
```

A capability provider creates it during an active owner turn:

```java
PlatformPromise pending = runtime.newPlatformPromise();
```

The provider returns that object as the language Promise and retains the same object in its platform callback. No separate resolver, `CompletableFuture`, coroutine continuation, or callback-specific owner `Runnable` is introduced.

## Foreign completion API

The completion surface preserves the Promise payload algebra:

```text
tryFulfillVoid
tryFulfillNumber
tryFulfillBoolean
tryFulfillReference

tryRejectVoid
tryRejectNumber
tryRejectBoolean
tryRejectReference
```

Number and boolean payloads remain primitive fields. Rejection reasons use the same void/number/boolean/reference categories as `JsPromise`.

Any thread may call these methods. The first platform call wins. A winning call:

1. records the completed state and payload;
2. marks the token queued;
3. calls `RuntimeInstance.admitHostTask(this)`.

A losing call never queues another task.

The boolean result answers whether that invocation won the platform token's first-completion race. It does not guarantee delivery when runtime shutdown has already begun.

## Owner settlement

`PlatformPromise.execute` runs only as an ordinary owner host task:

```text
platform callback
    -> copy or retain payload
    -> claim PlatformPromise
    -> coalesced runtime owner wake
    -> PlatformPromise.execute on owner
    -> JsPromise fulfillment or rejection
    -> complete microtask/rejection checkpoint
```

The foreign callback never calls `JsPromise.fulfill*`, `reject*`, a reaction, or generated TypeScript.

Even a platform callback invoked synchronously on the owner thread takes this host-task path. It does not settle inline inside the capability call.

If owner-side code already locked or settled the Promise before the captured platform completion is delivered, the platform settlement attempt loses normally and any retained reference payload is disposed.

## First-completion synchronization

Publication uses the `PlatformPromise` object's monitor.

This is deliberate:

- the transition occurs at most once per platform operation;
- the uncontended JVM/ART monitor fast path requires no per-instance helper object;
- no `AtomicInteger` allocation is added to every operation;
- no reflective `AtomicIntegerFieldUpdater` field name must survive R8 renaming;
- payload fields and completion state become visible together;
- competing callbacks receive exact first-wins behavior.

The monitor protects only token state and copied/retained payload fields. It is never held while executing a disposer, entering the runtime queue, settling `JsPromise`, or running TypeScript.

## Reference ownership and disposal

Ordinary Java references can use overloads without a disposer and rely on JVM garbage collection.

A copied or retained reference with external cleanup uses:

```java
tryFulfillReference(value, disposer)
tryRejectReference(reason, disposer)
```

Ownership moves into the completion method regardless of its boolean result.

The disposer runs exactly once when:

- another platform completion already won;
- the runtime was closed before admission;
- shutdown removed the queued completion before delivery;
- owner-side Promise resolution already won;
- publishing the owner wake failed and the exact admission was removed.

Successful Promise settlement transfers ownership to the Promise and does not invoke the disposer.

A disposer may run on the producer thread, owner thread, or runtime-close thread. It must be thread-safe, must not execute TypeScript, and should not throw. The runtime reports a non-fatal exception thrown during queued-task discard as `RuntimeErrorPhase.DISCARD`.

## Admission failure

`OwnerExecutor.post` has a strict failure contract: if it throws, the callback was not enqueued.

`RuntimeInstance.admitHostTask` therefore removes the exact `AdmittedTask` node whose wake could not be published and calls its `discard()` method before rethrowing the owner-post failure. This prevents a retained platform payload or inert completion from being stranded in the ingress queue.

If removal loses a race, runtime shutdown has already polled that exact node and owns disposal.

## Shutdown races

`RuntimeInstance.close()` first stops new admission, then discards queued host work.

Completion and close may race in either order:

```text
completion publishes first
    -> close removes queued PlatformPromise
    -> disposer runs once

close publishes first
    -> admitHostTask refuses PlatformPromise
    -> disposer runs once

completion passes first accepting check while close runs
    -> exact node is either removed by producer or polled by close
    -> one side, never both, performs disposal
```

A previously posted reusable owner wake may still arrive after close, but it observes the closed runtime and executes no TypeScript.

## Rejected alternatives

### `CompletableFuture`

Rejected as the platform-to-language bridge. It adds another completion object and executor semantics, boxes primitive results through generic APIs, and still requires an owner/microtask adapter.

### Kotlin coroutines

Rejected as the language or capability completion contract. A capability may internally use a coroutine when explicitly selected, but it must terminate at the same `PlatformPromise` owner-admission boundary rather than exporting coroutine scheduling semantics.

### Separate resolver and host-task objects

Rejected as the default path. They share the operation lifetime and checked payload category, so separate allocations provide no semantic benefit.

### One Android `Runnable` per completion

Rejected. Platform completions enter the runtime MPSC ingress queue and share one coalesced Handler wake.

### Direct foreign settlement

Rejected. It races Promise state and reaction lists, runs rejection bookkeeping on the wrong thread, and could execute generated TypeScript outside its owner.

### One `AtomicInteger` per operation

Rejected as avoidable allocation. The one-shot monitor transition is sufficient and does not depend on reflective field names under R8.

### Guessing cleanup from `AutoCloseable`

Rejected. External ownership is capability-specific. The explicit disposer defines whether and how a retained payload is released.

## Consequences

- The common platform operation adds one specialized `PlatformPromise` object, not a Promise plus completer/future/task stack.
- Primitive completion payloads remain unboxed.
- Competing worker callbacks have exact first-completion-wins behavior.
- Platform completion bursts share the runtime's one owner wake.
- Promise settlement and reactions remain owner-confined.
- Reference ownership remains explicit across losing, shutdown, and owner-overridden paths.
- Fetch, WebSocket, AbortSignal, body streams, and error projection remain separate capability algorithms layered on this boundary.
- No JNI transition is introduced for Java/Android completion delivery.

## Required evidence

Permanent tests and structural gates must prove:

- an owner-thread completion still settles only from a later host task;
- foreign completion does not settle `JsPromise` or execute a reaction directly;
- void, number, boolean, and reference fulfillment remain exact;
- void, number, boolean, and reference rejection remain exact;
- a burst of completions posts one coalesced owner wake;
- concurrent fulfillment/rejection attempts produce exactly one winner and one host task;
- a losing retained reference is disposed exactly once;
- owner-side settlement disposes a later captured retained reference;
- close-before-completion and close-after-admission both dispose exactly once;
- repeated completion-versus-close races neither leak nor double-dispose;
- a failed owner post removes and discards the exact admission;
- separate runtime instances retain separate queues and owners;
- `PlatformPromise` extends `JsPromise`, implements `RuntimeTask`, and does not implement `Runnable`;
- no per-operation `AtomicInteger`, `CompletableFuture`, coroutine, executor, or platform callback wrapper enters the core path.
