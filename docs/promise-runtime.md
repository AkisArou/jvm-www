# Promise runtime and compiler ABI

Status: first direct-JVM core implemented; async-function lowering is not yet integrated.

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

## Async/await integration

The compiler will generate one continuation object per suspended async invocation. Live-across-await locals become fields; other locals remain JVM locals. The continuation attaches as a Promise reaction and resumes only on the runtime owner. The synchronous prefix runs inside the caller's current host turn, and an already-settled await still resumes through the microtask queue.

The runtime ABI is ready for this lowering, but the unreachable ScriptC checkpoint recorded in `compiler-checkpoint.md` currently prevents end-to-end emitter integration from a clean remote checkout.

## Current exclusions

The first slice does not claim support for:

- dynamic thenable assimilation;
- `Promise.all`, `race`, `allSettled`, `any`, or `withResolvers`;
- compiler-generated `async`/`await` state machines;
- a user-visible `finally` method independent of compiler lowering;
- Web or Node rejection events;
- timers or platform Promise adapters.

Each exclusion remains explicit until its ordering and ownership tests exist.
