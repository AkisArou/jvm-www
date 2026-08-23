# Promise runtime and compiler ABI

Status: Promise core and fused async-frame ABI implemented; ScriptC async-state emission is not yet integrated end to end.

## Goals

The Promise object preserves ECMAScript-observable ordering while exposing a specialization-friendly Java ABI to checked ScriptC lowering. It is not a wrapper over `CompletableFuture`, an executor, or a coroutine library.

Every operation is owner-confined. A network, timer, or Android callback on another thread first publishes a transport-safe host task; that task settles the Promise while the runtime owner is executing.

## Representation

`JsPromise` contains:

```text
int state                 pending / fulfilled / rejected
int payloadKind           void / number / boolean / reference
double numberPayload
boolean booleanPayload
Object referencePayload
boolean resolutionLocked
reaction head/tail
rejection tracking flags
```

The state distinguishes fulfillment from rejection, so rejection reasons use the same unboxed payload algebra. JVM garbage collection owns reference lifetime. No process-global Promise registry exists.

## Reaction ABI

A compiler-generated handler implements:

```java
void execute(RuntimeInstance runtime, JsPromise source, JsPromise destination)
    throws Throwable;
```

The source is settled. The handler reads the statically selected payload slot and resolves the destination directly. If it returns without resolving, the runtime fulfills the destination with the void/`undefined` payload. If it throws `JsThrownValue`, the exact tagged JavaScript value rejects the destination. Another non-fatal Java failure currently rejects with that failure object as a reference; the later Error/Web layer can project platform exceptions into richer JavaScript Error objects.

A missing handler propagates the source settlement. The source's reaction list is FIFO, detached at settlement, and appended to the runtime microtask queue. Attaching to an already-settled Promise takes the same queue path.

## Resolution and adoption

Resolve and reject are once-only. The lock is taken before following another Promise, not when the followed Promise eventually settles. Therefore:

```text
destination.resolveWith(pendingSource)
destination.fulfillNumber(1)       -> ignored
pendingSource.fulfillNumber(2)     -> destination eventually fulfills with 2
```

A Promise may adopt only another `JsPromise` owned by the same `RuntimeInstance` in this static-profile slice. Self-resolution rejects with a `JsTypeError`. Adoption always uses a microtask, including an already-settled source. Arbitrary dynamic thenables are not approximated.

## Rejection observation

A rejection enters a per-instance candidate queue. After the outermost host turn drains microtasks to exhaustion, the runtime reports candidates that are still unhandled. Attaching any `then` reaction marks the source handled at attachment time. If a report already occurred, the host tracker receives one later-handled notification.

The tracker is deliberately not a user callback. Web `unhandledrejection` or Node `unhandledRejection` compatibility modules must turn the notification into the host task/event behavior defined by their selected profile.

## Fused async-frame ABI

`AsyncFrame` is the compiler-facing state-machine base. One generated instance is simultaneously:

```text
returned result Promise
+ live-across-await continuation fields
+ intrusive source-Promise reaction job
+ queued runtime microtask
```

The synchronous prefix runs through `start()` in the caller's active turn. Generated state zero receives no awaited value. A state that suspends calls `suspendOn(source, nextState)` and returns. The source links the frame itself into its FIFO reaction list, so no wrapper node or `Runnable` is allocated. When the source settles, the same frame runs as a microtask.

Await readers preserve specialization and rejection flow:

```text
awaitVoid
awaitNumber
awaitBoolean
awaitReference
```

A rejected source throws `JsThrownValue` at the generated await site. The compiler can therefore emit ordinary JVM exception edges for `try`/`catch`/`finally` across suspension.

A source-level `return promise` uses `adoptResult(source)`. The frame itself becomes the adoption job, preserving asynchronous resolution and first-settle-wins without allocating the generic adoption wrapper.

Suspension/adoption requests are staged and committed only after the generated state returns normally. A state that throws after requesting suspension rejects the frame and is never left subscribed to the source.

The normative rationale and lowering contract are recorded in [decision 0001](decisions/0001-fused-async-frame.md).

## Current exclusions

The current runtime does not claim support for:

- dynamic thenable assimilation;
- `Promise.all`, `race`, `allSettled`, `any`, or `withResolvers`;
- end-to-end ScriptC emission of async state machines;
- a user-visible `finally` method independent of compiler lowering;
- Web or Node rejection events;
- timers or platform Promise adapters.

Each exclusion remains explicit until its ordering and ownership tests exist.
