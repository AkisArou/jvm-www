# jvm-www

A direct-JVM JavaScript/TypeScript runtime and Web-compatible capability layer for [Native TypeScript](https://github.com/AkisArou/native-typescript).

The project exists to support TypeScript compiled to Java/DEX and executed by ART without embedding a JavaScript VM and without crossing JNI for ordinary Java or Android work.

## Scope

`jvm-www` is split along semantic boundaries rather than copying React Native as one monolithic runtime:

- **runtime core** — owner-confined turns, microtasks, promises, rejection tracking, timers, platform-completion adapters, and compiler-facing continuation support;
- **Android host adapter** — `Looper`/`Handler` ownership, monotonic deadline arming, lifecycle integration, and foreign-thread admission;
- **Web capabilities** — events, cancellation, encoding, console, Fetch, URL, blobs, WebSocket, and related APIs;
- **transport adapters** — replaceable platform plumbing such as OkHttp that publishes transport-safe snapshots but owns no JavaScript scheduling semantics;
- **conformance** — exact observable traces compared with the existing ScriptC C/LLVM backends and reference JavaScript engines.

React Native is useful as an API inventory and integration reference. ECMAScript, WHATWG specifications, and Native TypeScript's selected compatibility profile define behavior.

## Current slice

The current implementation provides a pure-Java `RuntimeInstance`, Promise core, compiler-facing async-frame ABI, logical timeout/interval timers, Android Handler host, platform Promise adapter, owner-confined events and abort signals, exact UTF-8 Encoding API objects, an owner-confined Console core, a buffered transport-independent Fetch core, and an OkHttp Fetch transport with:

- explicit allocation-free outer host-turn entry/exit calls for generated code;
- one owner thread per runtime instance;
- FIFO microtasks drained to exhaustion after the outermost turn;
- no owner wake when a microtask is queued during an active turn;
- thread-safe foreign host-task admission and one coalesced idle wake;
- a host-task fairness budget without truncating a microtask checkpoint;
- unboxed Promise payload slots for number, boolean, reference, and void;
- asynchronous `then`/`catch` reactions, pass-through handlers, first-settle-wins, and native-Promise adoption;
- exact arbitrary thrown-value propagation through `JsThrownValue`;
- checkpoint-based unhandled and later-handled rejection tracking;
- an `AsyncFrame` whose result Promise, live continuation fields, and resume microtask are one object;
- wrapper-free sequential awaits and wrapper-free async result adoption;
- one per-runtime deadline/sequence heap shared by `setTimeout` and `setInterval`;
- exact clamp-and-truncate delay coercion, generation-safe 53-bit handles, and eager cross-kind cancellation;
- non-overlapping intervals re-armed from callback completion with a fresh FIFO sequence;
- cancellation from an interval callback or its microtask checkpoint before re-arm;
- one reusable platform timer alarm with a full microtask checkpoint between timer callbacks;
- one `HandlerRuntimeHost` that is both `OwnerExecutor` and `TimerHost`;
- direct `Handler.post` owner wakes and absolute `Handler.postAtTime` timer alarms;
- the exact `SystemClock.uptimeMillis` time base with upward deadline rounding;
- a `PlatformPromise` that is simultaneously the returned `JsPromise`, first-completion token, and admitted `RuntimeTask`;
- exact reference disposal when a completion loses, owner settlement wins, admission fails, or runtime shutdown races delivery;
- owner-confined `EventTarget`, `Event`, `CustomEvent`, `AbortController`, and `AbortSignal` semantics;
- `TextEncoder` with exact scalar conversion, U+FFFD lone-surrogate replacement, and no-partial-scalar `encodeInto`;
- a streaming `TextDecoder` with WHATWG UTF-8 replacement boundaries, fatal errors, and BOM state;
- explicit rejection of unreached legacy decoder labels instead of delegating them to JVM charset aliases;
- per-Console count entries, timer entries, group depth, assertion behavior, and synchronous printer ordering;
- recursive `%s`, `%d`, `%i`, `%f`, `%o`, `%O`, and `%c` consumption with a compiler/profile conversion boundary;
- a pluggable `ConsoleSink`, monotonic `ConsoleClock`, deterministic duration output, and an always-available discard sink;
- no process-global console maps, background logger, Android Log dependency, or one `Runnable` per console message;
- a buffered Fetch profile with `Headers`, `Request`, `Response`, exact abort reasons, and one-shot body consumption;
- Fetch text bodies decoded through the same Web Encoding implementation;
- a `FetchOperation` fused across returned Promise, transport callback, abort algorithm, and admitted host task;
- an OkHttp adapter whose bridge is both the OkHttp callback and Fetch cancellation handle;
- complete response buffering and resource closure on the OkHttp callback thread before owner admission;
- explicit application ownership of the supplied OkHttp `Call.Factory` and all client policy;
- no per-operation `AtomicInteger`, Future, coroutine continuation, scheduler task, platform `Runnable`, live OkHttp response, or JVM charset shortcut crossing into language semantics;
- deterministic Java 8 conformance tests plus executable Node ordering references.

End-to-end ScriptC lowering, dynamic thenable assimilation, Promise combinators, Android application packaging/lifecycle wiring, an Android console sink, legacy Web encodings, WHATWG URL parsing, streaming Fetch bodies, and the remaining Web capabilities are separate incremental slices.

## Android attachment

Create the host on the runtime's owner Looper and pass the same object as both core capabilities:

```java
HandlerRuntimeHost host = HandlerRuntimeHost.forCurrentLooper();
RuntimeInstance runtime = new RuntimeInstance(host, errorReporter, host);
```

The application must close the runtime on that Looper before releasing its component. JavaScript timer semantics remain in `runtime-core`; the Android module only posts the reusable owner wake and the one earliest-deadline alarm.

## Platform Promise completion

Create the pending Promise during an active owner turn, return that same object to generated code, and retain it in the platform callback:

```java
PlatformPromise pending = runtime.newPlatformPromise();
transport.start(
        value -> pending.tryFulfillReference(value, responseDisposer),
        error -> pending.tryRejectReference(error));
return pending;
```

The platform callback only publishes a copied or retained payload. The owner ingress task performs the actual `JsPromise` settlement, and reactions then run as microtasks. A burst of completions shares the runtime's one coalesced Handler wake.

## Web UTF-8 encoding

Encoding objects belong to the runtime owner. The first decoder profile accepts the WHATWG UTF-8 labels and explicitly rejects unreached legacy encodings:

```java
runtime.enterHostTurn();
try {
    TextEncoder encoder = new TextEncoder(runtime);
    byte[] bytes = encoder.encode("hello \ud83d\udca9");

    TextDecoder decoder = new TextDecoder(runtime, "utf-8");
    String text = decoder.decode(bytes);
} finally {
    runtime.leaveHostTurn();
}
```

`TextDecoder.decode(input, true)` preserves a split UTF-8 sequence for the next call; `decode()` flushes it. The implementation owns malformed-sequence grouping and BOM behavior directly rather than inheriting them from `java.nio.charset`.

## Web console

Console state belongs to one runtime while final presentation belongs to a synchronous host sink:

```java
ConsoleSink sink = applicationConsoleSink;

runtime.enterHostTurn();
try {
    Console console = new Console(runtime, sink);
    console.log("loaded %d records", 3);
    console.group("network");
    console.time("request");
    // ...
    console.timeEnd("request");
    console.groupEnd();
} finally {
    runtime.leaveHostTurn();
}
```

The JVM ABI uses `assertCondition` for JavaScript `console.assert`, because `assert` is a Java keyword. `ConsoleSink` receives the distinct printer level, current group depth, and processed arguments synchronously. Generated bindings may supply a `ConsoleValueFormatter` with exact ECMAScript conversions; the default formatter is limited to ordinary Java values in the current static profile.

## Buffered Fetch over OkHttp

The application supplies and owns its configured OkHttp client. The adapter maps Fetch transport snapshots to calls and buffers each completed body before publishing it to the runtime:

```java
OkHttpClient client = new OkHttpClient.Builder().build();
FetchTransport transport = new OkHttpFetchTransport(client);

runtime.enterHostTurn();
try {
    JsPromise response = Fetch.fetch(runtime, transport, "https://example.test/data");
    // Return or attach generated Promise reactions here.
} finally {
    runtime.leaveHostTurn();
}
```

Cookies, caches, proxies, authentication, TLS, redirects, dispatcher behavior, and client lifecycle remain explicit application policy. The adapter never settles a Fetch Promise directly and never hands a live OkHttp `ResponseBody` to owner-confined code.

## Run conformance

A JDK is the only requirement for the permanent gates. Android and OkHttp boundaries compile against deterministic test-only API doubles, so ownership and resource behavior are exercised without an Android SDK, network, DNS, TLS, or external dispatcher:

```sh
./scripts/test-core.sh
./scripts/test-encoding.sh
./scripts/test-console.sh
./scripts/test-fetch.sh
./scripts/test-fetch-okhttp.sh
```

All production Java compiles against the Java 8 API surface.

## Compatibility profiles

The intended default is a mobile Web profile:

- ECMAScript Promise jobs and `queueMicrotask` share the microtask queue;
- timers, network completions, socket messages, and Android callbacks are host tasks;
- Node-only ordering such as `process.nextTick` is excluded unless an explicit Node-compatibility profile is selected;
- unsupported shapes fail precisely rather than being silently approximated.

See [`docs/architecture.md`](docs/architecture.md), [decision 0001](docs/decisions/0001-fused-async-frame.md), [decision 0002](docs/decisions/0002-one-armed-logical-timers.md), [decision 0003](docs/decisions/0003-android-handler-runtime-host.md), [decision 0004](docs/decisions/0004-fused-platform-promise.md), [decision 0005](docs/decisions/0005-owner-confined-web-events-and-abort.md), [decision 0006](docs/decisions/0006-buffered-fetch-core.md), [decision 0007](docs/decisions/0007-buffered-okhttp-fetch-transport.md), [decision 0008](docs/decisions/0008-exact-utf8-encoding-api.md), [decision 0009](docs/decisions/0009-owner-confined-console.md), and the [`Web/API inventory`](docs/web-api-inventory.md).
