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

The first implementation slice provides a pure-Java `RuntimeInstance` with:

- explicit allocation-free outer host-turn entry/exit calls for generated code;
- one owner thread per runtime instance;
- FIFO microtasks drained to exhaustion after the outermost turn;
- no owner wake when a microtask is queued during an active turn;
- thread-safe foreign host-task admission;
- one coalesced owner wake for an idle admission burst;
- a host-task fairness budget without truncating a microtask checkpoint;
- deterministic, dependency-free conformance tests.

It deliberately does **not** implement Promise yet. The scheduler contract is the foundation that Promise reactions and compiler-generated async continuations will use.

## Run the core conformance tests

A JDK is the only requirement:

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

See [`docs/architecture.md`](docs/architecture.md).
