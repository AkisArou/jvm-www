# 0001 — Fuse the async result Promise, continuation frame, and resume job

Status: accepted and implemented in `runtime-core`; ScriptC emitter integration remains pending.

## Context

A direct-JVM async function needs three conceptual things:

1. a Promise returned to the caller;
2. storage for captures and locals that survive an `await`;
3. a microtask that resumes the state machine when the awaited Promise settles.

A generic JVM design would commonly allocate three separate objects for those roles, or delegate them to `CompletableFuture`, Kotlin coroutines, an executor task, or one Android `Runnable` per reaction. Those designs add allocation and scheduling layers while still requiring an adapter to recover ECMAScript ordering.

Native TypeScript has checked whole-program IR. The compiler knows the fulfillment payload category, the reached captures, the locals live across each suspension, and every resume state. The runtime should expose an ABI that lets generated code preserve that information rather than erase it behind generic Java abstractions.

## Decision

One compiler-generated `AsyncFrame` object represents one async invocation and serves all three roles:

```text
AsyncFrame instance
    = returned JsPromise
    + generated live-across-await fields
    + intrusive Promise reaction job
    + queued RuntimeTask used for resume
```

`AsyncFrame` extends `JsPromise` and implements the internal intrusive `JsPromise.PromiseJob` contract. A source Promise links the frame itself into its FIFO reaction list and later appends that same frame to the runtime microtask queue. No wrapper node and no per-resume `Runnable` are created.

The frame owns its `RuntimeInstance`; there is no thread-local or process-global current runtime.

## Compiler lowering contract

A generated async factory has this shape:

```java
static JsPromise load(RuntimeInstance runtime, String id) {
    Load$Async frame = new Load$Async(runtime, id);
    frame.start();
    return frame;
}
```

The initial `start()` call executes state zero immediately inside the caller's active language turn. This preserves the synchronous-prefix rule.

A generated state must perform exactly one terminal action before returning:

- settle the frame with `fulfill*` or `reject*`;
- request result adoption with `adoptResult(source)`; or
- request an await with `suspendOn(source, nextState)`.

`executeState` returning without any of those actions rejects the frame with a precise internal `IllegalStateException`. It is treated as a compiler/runtime ABI violation rather than silently leaving a Promise pending.

Locals that survive an `await` become fields on the generated subclass. Locals proven dead at the suspension remain ordinary JVM locals. Generated code must not use a generic `Object[]` frame for statically known locals.

## Suspension commit rule

`suspendOn` and `adoptResult` stage a request. The runtime installs the source-Promise subscription only after the generated state method returns normally.

This is required because the incorrect ordering:

```text
subscribe frame to source
then generated state throws
```

would reject the frame but leave it attached to the source, allowing a later settlement to queue and execute an already-finished frame. Staging makes the operation transactional:

```text
state returns normally -> commit one subscription
state throws           -> clear request and reject frame
```

## Await behavior

When the source settles, the source queues the same frame as a microtask. The frame resumes only on its runtime owner.

The runtime exposes specialized await readers:

```text
awaitVoid
awaitNumber
awaitBoolean
awaitReference
```

They throw `JsThrownValue` when the source rejected, preserving an arbitrary JavaScript rejection value at the generated await site. This lets ordinary generated `try`/`catch`/`finally` control flow handle rejection across suspension.

Awaiting an already-settled Promise still links and queues the frame through the microtask queue. It never resumes inline.

## Result adoption

A source-level `return promise` should use `AsyncFrame.adoptResult`, not the generic `JsPromise.resolveWith` path.

The frame itself becomes the adoption job:

```text
returned frame Promise
    follows source Promise
    and is queued as the source's continuation
```

This preserves first-resolution-wins and asynchronous adoption while avoiding the generic adoption wrapper allocation. Generic non-frame Promises continue to use the ordinary adoption job.

## Ordinary Promise reactions

This decision does not yet require `.then()` destinations to fuse with their generic reaction jobs. The async-frame allocation is the first measured architectural target because every suspended async invocation needs it. Generic reaction fusion can be evaluated separately with allocation evidence and retention tests; it must not be assumed to be a free win because retaining handler/source fields on a long-lived destination Promise can extend object lifetimes.

## Rejected alternatives

### `CompletableFuture`

Rejected as the language Promise. Its completion and executor model does not define ECMAScript Promise-job ordering, arbitrary thrown values, owner confinement, or the required checkpoint boundary.

### Kotlin coroutines

Rejected as the language semantics and as a mandatory runtime dependency. They can be a benchmark comparison or a platform adapter, but generated TypeScript must not inherit coroutine dispatch/cancellation semantics accidentally.

### One `Runnable` per reaction or resume

Rejected. Microtasks are runtime jobs. Android sees one coalesced reusable owner wake, not one Looper callback for every Promise continuation.

### Separate Promise, frame, and task objects

Rejected as the default lowering because the three objects have the same invocation lifetime and checked IR provides enough information to generate one specialized class. A future compiler case may deliberately split them when escape/lifetime evidence proves that it is better, but that must be an explicit optimization, not the baseline.

### Generic `Object[]` continuation storage

Rejected for checked static locals. It boxes primitives, loses field types, adds indexed access, and prevents ART from optimizing ordinary field loads. Dynamic values may still require a tagged representation at an explicit dynamic boundary.

## Consequences

- One object represents the common suspended async invocation.
- Primitive Promise results remain in specialized slots.
- Sequential awaits reuse the same frame/job object.
- There is no JNI transition on the direct JVM path.
- There is no executor/coroutine dependency and no reaction-specific `Runnable`.
- The ScriptC emitter must generate subclasses of `AsyncFrame` from checked IR; this repository does not parse TypeScript or reinterpret AST nodes.
- `try`/`catch`/`finally`, dynamic thenables, non-Promise await coercion, and compiler-emitted state graphs still require their own end-to-end ScriptC integration tests.

## Required evidence

The permanent gate must prove:

- eager synchronous prefix execution;
- pending and already-settled await both resume as microtasks;
- sequential awaits reuse one frame;
- rejection is catchable at the generated await site;
- an uncaught rejection rejects the frame with the exact payload;
- a synchronous throw rejects rather than escaping to the caller;
- result adoption is asynchronous and uses the frame as the job;
- a throw after a staged suspension leaves no stale subscription;
- `AsyncFrame` extends `JsPromise`, implements `RuntimeTask`, and does not implement `Runnable`;
- no `CompletableFuture` or Kotlin coroutine dependency enters `runtime-core`.
