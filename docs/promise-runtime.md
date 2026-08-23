# Promise runtime and compiler ABI

Status: Promise core, fused async-frame ABI, and fused platform-completion ABI implemented; ScriptC async-state emission is not yet integrated end to end.

## Goals

The Promise object preserves ECMAScript-observable ordering while exposing a specialization-friendly Java ABI to checked ScriptC lowering. It is not a wrapper over `CompletableFuture`, an executor, or a coroutine library.

Language Promise state remains owner-confined. A network, socket, Android, or other platform callback on another thread may only publish a transport-safe completion and admit a host task; that host task settles the Promise while the runtime owner is executing.

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

The state distinguishes fulfillment from rejection, so rejection reasons use the same unboxed payload algebra. JVM garbage collection owns ordinary reference lifetime. No process-global Promise registry exists.

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

## Fused platform-completion ABI

A platform-backed operation begins on the owner:

```java
PlatformPromise pending = runtime.newPlatformPromise();
```

The same object is returned as the language `JsPromise`, retained as the platform first-completion token, and admitted as the host `RuntimeTask`. This eliminates a separate resolver object, future, completion wrapper, and queue-task wrapper specific to the capability.

The foreign API is specialized:

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

Number and boolean values stay in primitive fields. The first platform completion wins under the object's monitor, records its payload, and calls `RuntimeInstance.admitHostTask(this)`. The worker thread never invokes `JsPromise.fulfill*`, `reject*`, a reaction, or generated TypeScript.

On the owner, `PlatformPromise.execute` performs the ordinary Promise settlement. Each platform completion is therefore a host task with a complete reaction/microtask checkpoint before the next host task.

Reference overloads may receive a `PlatformReferenceDisposer`. Ownership moves into the completion call even when it loses. The disposer runs exactly once when:

- another platform completion already won;
- runtime shutdown wins before delivery;
- owner-side Promise settlement already locked the destination;
- owner-wake publication fails and the admission is removed.

A successful reference settlement transfers ownership into the Promise and does not invoke the disposer. The object's monitor is used instead of allocating one `AtomicInteger` per platform operation or using a reflection-sensitive field updater under R8.

The normative ownership, shutdown, and race contract is recorded in [decision 0004](decisions/0004-fused-platform-promise.md).

## Current exclusions

The current runtime does not claim support for:

- dynamic thenable assimilation;
- `Promise.all`, `race`, `allSettled`, `any`, or `withResolvers`;
- end-to-end ScriptC emission of async state machines;
- a user-visible `finally` method independent of compiler lowering;
- Web or Node rejection events;
- cancellation of an underlying platform operation merely because a Promise becomes unreachable;
- Fetch, WebSocket, or stream semantics beyond the generic platform-completion boundary.

Each exclusion remains explicit until its ordering and ownership tests exist.
