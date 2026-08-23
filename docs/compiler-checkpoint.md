# Compiler integration checkpoint

Status checked: 2026-08-23.

The Native TypeScript parent repository at `3ff5680d43a96679170d54b723ceb61a6de00a90`
points its `third_party/scriptc` gitlink at:

```text
7759153c975ac56ce1bfe3b642736228de03af23
```

At the time of this check, GitHub did not resolve that object from the public
`AkisArou/scriptc` remote. The visible `native-typescript` branch was at
`5628346e56dcae419390ab15db9b464df13ad160`, and the direct-JVM checkpoint
referenced by the parent could not be fetched by SHA.

This does not block standalone Java runtime and Web-capability work in
`jvm-www`. It does mean end-to-end compiler integration and regression testing
cannot be reproduced from remote checkouts until the pinned ScriptC commit is
restored or another reachable checkpoint containing the same JVM emitter work
is supplied.

The stable scheduler entry points are:

```java
runtime.enterHostTurn();
try {
    // generated synchronous body or platform callback
} finally {
    runtime.leaveHostTurn();
}

runtime.queueMicrotask(generatedRuntimeTask);
runtime.admitHostTask(copiedPlatformCompletion);
```

The compiler-facing timer ABI is:

```java
double timeout = runtime.setTimeout(generatedCallbackTask, delay);
double interval = runtime.setInterval(generatedCallbackTask, delay);
runtime.clearTimeout(timeout);
runtime.clearInterval(interval);
```

Timeout and interval handles share one numeric map, so either clear operation
may receive either handle. Trailing callback arguments belong in the generated
`RuntimeTask` capture; the Java timer queue does not add an argument-array or
per-tick wrapper.

The Android attachment ABI is concrete:

```java
HandlerRuntimeHost host = HandlerRuntimeHost.forCurrentLooper();
RuntimeInstance runtime = new RuntimeInstance(host, errorReporter, host);
```

The same host object is the owner executor and the one-alarm timer capability.
It posts the runtime's reusable callbacks directly to one Handler and uses
`SystemClock.uptimeMillis` with absolute `Handler.postAtTime` deadlines. The
compiler must not emit Handler calls, Android timer objects, or target-specific
Promise checkpoints.

The capability-facing platform Promise ABI is:

```java
PlatformPromise pending = runtime.newPlatformPromise();

platformOperation.start(
    value -> pending.tryFulfillReference(value, valueDisposer),
    error -> pending.tryRejectReference(error));

return pending;
```

For primitive results, capability bindings call the corresponding
`tryFulfillNumber`, `tryFulfillBoolean`, `tryRejectNumber`, or
`tryRejectBoolean` method and avoid boxing. The same `PlatformPromise` object is
the returned language Promise, foreign first-completion token, and admitted
host task.

A generated or handwritten capability binding must obey these rules:

- create `PlatformPromise` only during an active owner language turn;
- copy or retain transport-safe payloads before publishing them;
- never call owner-only `JsPromise.fulfill*` or `reject*` from a worker;
- treat reference arguments to disposer overloads as moved;
- never add `CompletableFuture`, coroutine dispatch, or a per-completion
  Android `Runnable`;
- keep Fetch, WebSocket, body, abort, and error semantics in their capability
  modules rather than hiding them inside `PlatformPromise`.

The Promise core and compiler-facing `AsyncFrame` ABI are implemented
independently of the unavailable emitter checkpoint. The accepted contracts are
recorded in:

```text
docs/promise-runtime.md
docs/decisions/0001-fused-async-frame.md
docs/decisions/0002-one-armed-logical-timers.md
docs/decisions/0003-android-handler-runtime-host.md
docs/decisions/0004-fused-platform-promise.md
```

A future ScriptC integration must consume checked IR and generate subclasses of
`AsyncFrame`; it must not reinterpret TypeScript AST independently. The emitter
may adapt exact method names as the reachable IR requires, but any incompatible
change to the fused async-frame, one-armed timer, Android host, or fused platform
Promise decisions must update the decision record and its conformance evidence
rather than silently introducing a parallel ABI.
