# jvm-www

A direct-JVM JavaScript/TypeScript runtime and Web-compatible capability layer for [Native TypeScript](https://github.com/AkisArou/native-typescript).

The project exists to support TypeScript compiled to Java/DEX and executed by ART without embedding a JavaScript VM and without crossing JNI for ordinary Java or Android work.

## Scope

`jvm-www` is split along semantic boundaries rather than copying React Native as one monolithic runtime:

- **runtime core** — owner-confined turns, microtasks, promises, rejection tracking, timers, and compiler-facing continuation support;
- **Android host adapter** — `Looper`/`Handler` ownership, monotonic deadline arming, lifecycle integration, and foreign-thread admission;
- **Web capabilities** — `AbortController`, `console`, Fetch, WebSocket, encoding, URL, blobs, and related APIs;
- **conformance** — exact observable traces compared with the existing ScriptC C/LLVM backends and reference JavaScript engines.

React Native is useful as an API inventory and integration reference. ECMAScript, WHATWG specifications, and Native TypeScript's selected compatibility profile define behavior.

## Current slice

The current implementation provides a pure-Java `RuntimeInstance`, Promise core, compiler-facing async-frame ABI, logical timeout/interval timers, and the first Android Handler host with:

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
- no adapter-owned callback wrapper, worker scheduler, or private Android polling loop;
- deterministic Java 8 conformance tests plus executable Node ordering references.

End-to-end ScriptC lowering, dynamic thenable assimilation, Promise combinators, Android application packaging/lifecycle wiring, and Web capabilities remain separate incremental slices.

## Android attachment

Create the host on the runtime's owner Looper and pass the same object as both core capabilities:

```java
HandlerRuntimeHost host = HandlerRuntimeHost.forCurrentLooper();
RuntimeInstance runtime = new RuntimeInstance(host, errorReporter, host);
```

The application must close the runtime on that Looper before releasing its component. JavaScript timer semantics remain in `runtime-core`; the Android module only posts the reusable owner wake and the one earliest-deadline alarm.

## Run the runtime conformance tests

A JDK is the only requirement. The test script compiles `runtime-android` against deterministic test-only `android.os` stubs, so the host boundary is exercised without an Android SDK. When Node is present, it also verifies the reference Promise ordering trace:

```sh
./scripts/test-core.sh
```

All production Java compiles against the Java 8 API surface.

## Compatibility profiles

The intended default is a mobile Web profile:

- ECMAScript Promise jobs and `queueMicrotask` share the microtask queue;
- timers, network completions, socket messages, and Android callbacks are host tasks;
- Node-only ordering such as `process.nextTick` is excluded unless an explicit Node-compatibility profile is selected;
- unsupported shapes fail precisely rather than being silently approximated.

See [`docs/architecture.md`](docs/architecture.md), [decision 0001](docs/decisions/0001-fused-async-frame.md), [decision 0002](docs/decisions/0002-one-armed-logical-timers.md), [decision 0003](docs/decisions/0003-android-handler-runtime-host.md), and the [`Web/API inventory`](docs/web-api-inventory.md).
