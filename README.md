# jvm-www

A direct-JVM JavaScript/TypeScript runtime and Web-compatible capability layer for [Native TypeScript](https://github.com/AkisArou/native-typescript).

The project exists to support TypeScript compiled to Java/DEX and executed by ART without embedding a JavaScript VM and without crossing JNI for ordinary Java or Android work.

## Scope

`jvm-www` is split along semantic boundaries rather than copying React Native as one monolithic runtime:

- **runtime core** — owner-confined turns, microtasks, promises, rejection tracking, and compiler-facing continuation support;
- **Android host adapter** — `Looper`/`Handler` ownership, monotonic timers, lifecycle, and foreign-thread admission;
- **Web capabilities** — `AbortController`, `console`, Fetch, WebSocket, encoding, URL, blobs, and related APIs;
- **conformance** — exact observable traces compared with the existing ScriptC C/LLVM backends and reference JavaScript engines.

React Native is useful as an API inventory and integration reference. ECMAScript, WHATWG specifications, and Native TypeScript's selected compatibility profile define behavior.

## Current slice

The current implementation provides a pure-Java `RuntimeInstance`, Promise core, and compiler-facing async-frame ABI with:

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
- deterministic Java conformance tests plus an executable Node ordering reference.

End-to-end ScriptC lowering, dynamic thenable assimilation, Promise combinators, timers, and Web capabilities remain separate incremental slices.

## Run the core conformance tests

A JDK is the only requirement. When Node is present, the test script also verifies the reference Promise ordering trace:

```sh
./scripts/test-core.sh
```

The script compiles the core with the Java 8 API surface and runs the deterministic test harness.

## Compatibility profiles

The intended default is a mobile Web profile:

- ECMAScript Promise jobs and `queueMicrotask` share the microtask queue;
- timers, network completions, socket messages, and Android callbacks are host tasks;
- Node-only ordering such as `process.nextTick` is excluded unless an explicit Node-compatibility profile is selected;
- unsupported shapes fail precisely rather than being silently approximated.

See [`docs/architecture.md`](docs/architecture.md), [decision 0001](docs/decisions/0001-fused-async-frame.md), and the [`Web/API inventory`](docs/web-api-inventory.md).
