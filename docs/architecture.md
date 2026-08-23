# Architecture

## Semantic authority

The runtime targets a selected TypeScript/JavaScript compatibility profile, not an accidental mixture of Java, Android, Node, and browser behavior.

Priority is:

1. ECMAScript semantics for language facilities such as Promise jobs and async functions;
2. WHATWG semantics for selected Web APIs;
3. Native TypeScript's documented static restrictions and precise refusals;
4. React Native as an API inventory and a useful Android implementation reference.

A Java API with a similar name is not automatically a compatible implementation. `CompletableFuture`, Kotlin coroutines, `java.net.URI`, and Java charset convenience methods may be useful implementation components or adapters, but they do not define JavaScript-observable behavior.

## Runtime/compiler boundary

The compiler owns:

- async-function state-machine lowering;
- live-across-`await` continuation fields;
- typed closure and reaction classes;
- Promise payload specialization selected from checked IR;
- precise rejection of unsupported source or IR shapes.

`jvm-www` owns:

- the stable Java ABI called by generated continuations;
- Promise settlement and reaction scheduling;
- microtask checkpoints and rejection observation;
- the logical timer heap, timer identifiers, and selected-profile coercion rules;
- host scheduling, foreign-completion, and transport interfaces;
- the Android `Looper`/`Handler` implementation of those host interfaces;
- Web-compatible API state machines and capability implementations.

The Android application target owns:

- selecting the runtime's owner `Looper`;
- creating and retaining the `HandlerRuntimeHost`;
- lifecycle entry points and closing the runtime on its owner;
- packaging support classes into DEX;
- app-specific permissions and capability selection.

The Android adapter does not own JavaScript timer or Promise semantics. Likewise, an HTTP or
WebSocket transport does not own Fetch/WebSocket object semantics; transports only execute
platform I/O and publish copied or retained completions through `PlatformPromise` or the lower-level
runtime ingress queue.

## Scheduling model

Each `RuntimeInstance` is independent. It has one owner executor, one owner-confined microtask queue, one thread-safe foreign ingress queue, Promise/rejection state, and its own lazily allocated logical timer state.

An outer turn is:

```text
host callback enters
  -> generated TypeScript runs synchronously
  -> nested same-owner entries may occur
  -> outermost entry exits
  -> microtasks drain FIFO to exhaustion
  -> unhandled-rejection checkpoint
  -> control returns to the host executor
```

Microtasks are never represented as Android `Runnable` objects one-for-one. The runtime posts one reusable wake callback only when work is admitted while idle. Work queued during an active owner turn is consumed by that turn's checkpoint without a post.

A due timeout, interval, or platform completion is one host task. Each receives a complete microtask and rejection checkpoint before the next host task begins.

Foreign threads may claim a first completion and publish transport-safe payload state, but they cannot mutate `JsPromise`, run generated TypeScript, or touch owner-confined heap state. A completion admitted while the runtime is idle shares the same coalesced owner wake as every other host event.

## Fairness without semantic drift

A host wake may process only a bounded number of independently admitted host tasks before returning to the Looper. Each host task still receives a complete microtask checkpoint before the next host task. A microtask checkpoint itself is never truncated merely to satisfy an Android fairness budget; doing so would change ECMAScript ordering.

The same budget bounds the number of due timer callbacks handled by one platform alarm. Remaining due timers re-arm the one host alarm at their already-due deadline.

Runaway-job protection, if added, must be an explicit resource-limit failure mode rather than silently yielding as though another host task had begun.

## Profiles

The default planned profile is **Web Mobile**:

- ECMAScript Promise and microtask ordering;
- timers, Fetch, WebSocket, and Android callbacks as host tasks;
- Web-compatible abort and event objects;
- no Node-only `process.nextTick`, `ref`, `unref`, or filesystem globals unless separately selected.

An optional Node-compatibility profile may add those APIs later, with their distinct ordering. It must not alias `process.nextTick` to `queueMicrotask`.

The checked timer ABI preserves ScriptC's current Node-compatible delay clamp and truncation exactly. HTML nested-timer clamping is a separate future profile decision and must not be introduced implicitly by a platform adapter.

## Modules

```text
runtime-core             owner turns, jobs, Promise core, platform completions, rejection tracking, logical timers
runtime-testkit          deterministic executor/clock and trace corpus
runtime-android          Looper/Handler owner execution and absolute uptime alarm
runtime-android-testkit  deterministic test-only android.os model and adapter integration traces
web-events               EventTarget, Event, abort algorithms
web-encoding             TextEncoder/TextDecoder
web-fetch-core           Headers/Request/Response/body state and transport SPI
web-fetch-okhttp         optional Android OkHttp transport
websocket-core           WebSocket state machine and transport SPI
websocket-okhttp         optional Android OkHttp transport
web-url                  WHATWG-compatible URL and URLSearchParams
```

Dependencies point from host and capability modules toward `runtime-core`, never from
`runtime-core` toward Android, OkHttp, Kotlin, or another platform library. A dependency such as
OkHttp is a replaceable transport choice, not part of the public Fetch or WebSocket ABI. The
`android.os` classes in `runtime-android-testkit` exist only to compile and drive deterministic JVM
conformance; they are not a production dependency or artifact.

## Performance rules

The design is intended to exploit checked whole-program IR rather than recreate a generic JavaScript VM:

- generated code uses explicit `enterHostTurn`/`leaveHostTurn` calls, avoiding callback wrapper allocation;
- one reusable owner wake callback exists per runtime instance;
- Promise reactions are runtime jobs, not one `Runnable` each;
- primitive payload and continuation specialization belongs at the compiler/runtime ABI boundary;
- one generated async frame is the result Promise, continuation storage, and resume job;
- one `PlatformPromise` is the returned Promise, foreign completion token, and admitted host task;
- platform number and boolean results cross the ingress path without boxing;
- platform completion publication uses the Promise object's monitor and allocates no per-operation atomic holder;
- logical timers reuse slot entries and expose only one reusable platform alarm callback;
- firing intervals keep their slot out of the free list until the callback checkpoint completes;
- `HandlerRuntimeHost` posts the core callbacks directly and creates no adapter callback wrapper;
- foreign payloads are copied or retained once and decoded on the owner;
- no periodic polling loop;
- no JNI transition on the direct Android path.

Every optimization remains subordinate to observable ordering, ownership, and error behavior.

## Implemented Promise ABI

The Promise core uses one owner-confined `JsPromise` object with an integer state and specialized payload slots. It does not use Java generics to carry language values:

```text
state:        pending | fulfilled | rejected
payload kind: void | number | boolean | reference
double numberPayload
boolean booleanPayload
Object referencePayload
```

`then` registration creates one runtime reaction job, not one executor `Runnable`. A settled source queues that job; it never invokes the handler inline. Missing handlers copy the source settlement into the destination. Generated handlers resolve the destination directly, which lets the compiler preserve primitive specialization and select native-Promise adoption without a boxed generic return object.

A resolve/reject attempt locks the Promise immediately. Adoption of a pending Promise therefore prevents a later direct settlement from winning. Adoption itself resumes through the microtask queue even when the source is already settled. Dynamic thenable assimilation remains a precise unsupported shape until the checked IR can represent it.

Unhandled rejection candidates are recorded per runtime and observed after a complete microtask checkpoint. Attaching any `then` reaction marks the source handled, including the default thrower created by an omitted rejection callback; a propagated rejection belongs to the destination Promise. The tracker is a host hook and may not execute generated TypeScript directly.

## Implemented async-frame ABI

A compiler-generated `AsyncFrame` is simultaneously the async invocation's returned `JsPromise`, the object containing locals live across suspension, and the intrusive Promise job queued to resume the state machine. The source Promise links and queues the frame itself, so an await creates neither a wrapper queue node nor an Android `Runnable`.

The synchronous prefix executes through `start()` in the caller's active turn. `suspendOn` and `adoptResult` stage one operation and commit it only after the generated state returns normally. Awaiting an already-settled Promise still resumes through a microtask. Rejection is re-thrown at the await site through `JsThrownValue`, so generated JVM exception edges can preserve `try`/`catch`/`finally` behavior.

The full lowering contract, rejected alternatives, and permanent evidence requirements are normative in [decision 0001](decisions/0001-fused-async-frame.md).

## Implemented platform Promise ABI

A capability provider allocates a `PlatformPromise` during an active owner turn:

```java
PlatformPromise pending = runtime.newPlatformPromise();
```

That one object is:

```text
returned JsPromise
+ foreign first-completion token
+ transport-safe payload slot
+ admitted RuntimeTask
```

Any thread may call the specialized `tryFulfill*` or `tryReject*` methods. The first platform call
wins the token, records void/number/boolean/reference state without boxing primitives, and admits the
same object as one host task. Even an owner-thread callback cannot settle it inline; the owner host
task performs the actual `JsPromise` settlement and its reactions run in the following microtask
checkpoint.

Reference overloads may carry a `PlatformReferenceDisposer`. Ownership moves into the completion
call. A losing completion, runtime-close race, failed owner-wake publication, or owner-side
settlement that already locked the Promise disposes the retained reference exactly once. Successful
Promise settlement transfers the reference to the Promise and does not call the disposer.

First-completion publication uses the object's monitor fast path. This avoids a separate resolver,
future, task wrapper, or per-operation atomic-holder allocation and remains safe under R8 member
renaming. The exact ownership and race contract is normative in [decision 0004](decisions/0004-fused-platform-promise.md).

## Implemented logical timer ABI

`RuntimeInstance.setTimeout`, `setInterval`, `clearTimeout`, and `clearInterval` use one lazily allocated deadline/sequence min-heap. Both timer kinds share one positive-number handle map; either clear function cancels either kind. Slot generation is encoded into each exactly representable handle so stale cancellation cannot affect a reused timer entry.

The logical queue owns delay coercion, ordering, cancellation, interval re-arm, fairness, and callback lifecycle. `TimerHost` owns only a monotonic timestamp plus one replaceable absolute alarm. Adding or cancelling a timer touches the host only when the earliest deadline changes. There is no periodic pump, `ScheduledExecutorService`, or platform `Runnable` per timer.

Every due callback runs through the ordinary host-task entry path and therefore receives a full microtask and rejection checkpoint before another due callback. An interval remains addressable while that checkpoint runs, so cancellation from the callback or one of its microtasks prevents re-arm. Otherwise it is scheduled from callback-completion time with its original coerced delay and a fresh FIFO sequence; intervals never overlap or perform fixed-rate catch-up.

Cancellation and shutdown call `RuntimeTask.discard()` when a registration is retired, including an interval that may already have delivered earlier ticks. The full contract and handle representation are normative in [decision 0002](decisions/0002-one-armed-logical-timers.md).

## Implemented Android Handler host

`HandlerRuntimeHost` implements both `OwnerExecutor` and `TimerHost` over one Handler. A foreign
admission reaches `Handler.post`; owner identity is the configured Looper's identity. The timer
clock is `SystemClock.uptimeMillis` converted to nanoseconds, and an absolute logical deadline is
rounded upward to milliseconds before `Handler.postAtTime`.

Arming removes the previous callback and posts the supplied reusable timer wake directly. Disarming
removes it. The adapter contains no Promise state, logical timer entry, interval policy, delay
coercion, or microtask queue. A typical runtime passes the same host object to both constructor
capability slots:

```java
HandlerRuntimeHost host = HandlerRuntimeHost.forCurrentLooper();
RuntimeInstance runtime = new RuntimeInstance(host, errorReporter, host);
```

The exact clock, rounding, lifecycle, and allocation contract is normative in [decision 0003](decisions/0003-android-handler-runtime-host.md).

## Decision records

- [0001 — Fuse the async result Promise, continuation frame, and resume job](decisions/0001-fused-async-frame.md)
- [0002 — Keep logical timers in each runtime and arm one host deadline](decisions/0002-one-armed-logical-timers.md)
- [0003 — Bind one runtime to one Android Handler and uptime clock](decisions/0003-android-handler-runtime-host.md)
- [0004 — Fuse the platform Promise, completion token, and admitted task](decisions/0004-fused-platform-promise.md)
