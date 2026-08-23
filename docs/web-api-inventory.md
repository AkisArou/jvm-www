# Web and React Native compatibility inventory

This is a planning inventory, not a claim that every named API is implemented. A build should include only reached capabilities selected by its compatibility profile.

## Three different kinds of global

Treating every familiar global as the same kind of object leads to poor boundaries. The direct-JVM target separates them into:

1. **Language intrinsics** — ECMAScript values and operations whose semantics belong to ScriptC and compiler lowering.
2. **Runtime scheduling facilities** — per-instance jobs, Promise state, timers, errors, and clocks.
3. **Target capabilities** — networking, logging, files, sockets, Android UI integration, and other host services.

A Java class with the same name is not automatically compatible. For example, `CompletableFuture` is not Promise, `java.net.URI` is not the WHATWG URL parser, and Java's default malformed-Unicode replacement is not automatically `TextEncoder` behavior.

## Planned default: Web Mobile

The default profile should make common React Native-style TypeScript libraries practical without pretending an Android process is a browser or Node.js.

### Language and binary foundations

Owned primarily by ScriptC and the compiler/runtime ABI:

- `Promise`, `queueMicrotask`, and async-function continuations;
- ECMAScript errors and rejection values;
- `ArrayBuffer`, `DataView`, and typed arrays;
- collections, symbols, iterators, and other reached ECMAScript built-ins.

Primitive-specialized Promise payloads and continuation fields are selected from checked IR. The public behavior remains ECMAScript-compatible even when the representation is not generic.

### Environment aliases and diagnostics

Candidate globals:

- `globalThis` as the actual global object;
- `global`, `window`, and `self` as compatibility aliases, without creating a browser `Window`;
- `console` with formatting in the compatibility layer and a pluggable sink;
- `__DEV__` as a compile-time or generated build constant;
- a deliberately small `process.env.NODE_ENV` compatibility object only when selected;
- `navigator` as a narrow immutable capability description, not a browser navigator;
- `alert` only through an Android/UI capability, never from runtime-core.

The Android console sink can use `android.util.Log`. Desktop JVM tests use a deterministic recording sink. Formatting, grouping, counters, and timers should not be delegated blindly to Logcat.

### Scheduling and time

- `setTimeout`, `clearTimeout`, `setInterval`, and `clearInterval`;
- `requestAnimationFrame` and cancellation through a display-frame capability;
- `requestIdleCallback` only after its deadline and cancellation behavior is specified;
- `performance.now()` from a monotonic clock;
- `setImmediate` only in a profile that names its ordering; it is not an alias chosen for convenience.

Timers use a per-runtime deadline/sequence heap. An Android adapter arms one callback for the earliest deadline. Timer coercion and nested-timer behavior are compatibility-profile decisions; Node rules and HTML rules must not be mixed accidentally.

### Events and cancellation

- `EventTarget`, `Event`, `CustomEvent`, and the event classes needed by selected capabilities;
- `AbortController`, `AbortSignal`, abort reasons, and static helpers selected by the profile;
- listener ordering, `once`, removal during dispatch, and exception reporting covered by trace tests.

Abort state is owner-confined for language observation. A transport receives a thread-safe cancellation token or callback; worker threads do not dispatch TypeScript events directly.

### Encoding

The implemented first slice provides:

- `TextEncoder` with UTF-8 `encode` and `encodeInto`;
- exact conversion of lone UTF-16 surrogates to U+FFFD;
- `TextDecoder` for the WHATWG UTF-8 labels with replacement/fatal modes, BOM handling, and streaming state;
- one shared decoder implementation for direct API calls and `Response.text()`.

`encodeInto` never writes a partial encoded scalar and reports `read` in UTF-16 code units. The implementation does not use `String.getBytes`, `new String(bytes, charset)`, or a retained `CharsetDecoder` as semantic substitutes.

Legacy labels and encodings remain unreached. They fail with `JsRangeError` instead of being passed to Java charset aliases. Adding any legacy encoding requires the WHATWG label table, exact decoder/encoder indexes, malformed-input traces, and a separate decision record. Optional `atob`/`btoa` helpers are also separate profile choices.

### URL

- `URL` and `URLSearchParams` through a WHATWG-compatible parser and serializer;
- object-URL support only with the Blob capability and explicit lifecycle.

Do not use `java.net.URL` or `java.net.URI` as the semantic implementation. They may be transport inputs after WHATWG parsing has produced a normalized URL.

### Fetch and body types

- `fetch`, `Headers`, `Request`, and `Response`;
- `Blob`, `File`, `FormData`, and body-consumption state;
- `AbortSignal` cancellation;
- redirects, credentials/cookies, compression, and caching only when the selected profile defines them;
- streaming request/response bodies as a later explicit slice rather than a buffered API pretending to stream.

The public objects and algorithms live in Java compatibility modules. Transport is an interface. Android's primary implementation uses replaceable OkHttp plumbing, while deterministic tests use fake transports and API doubles. Worker callbacks copy or retain a transport-safe result, admit one host task, and settle the Native TypeScript Promise on the owner.

CORS is a browser security policy and is not silently invented for native applications. A profile may add an explicit policy layer.

### WebSocket

- browser-shaped constructor, constants, state transitions, events, `send`, `close`, and `binaryType`;
- OkHttp transport on Android;
- every incoming message is a separate host task with a microtask checkpoint before the next message handler;
- binary payloads use byte arrays, buffers, or segmented storage rather than base64 on the direct-JVM path.

Protocol validation, close-code handling, buffered amount, failure ordering, and lifecycle shutdown need conformance fixtures before implementation is declared compatible.

### React renderer support, not a fake browser DOM

React Native exposes several DOM-shaped constructors for renderer interoperability. Add only the reached subset required by the Native TypeScript React renderer, such as event targets, geometry values, or read-only native element handles.

Do not create a general browser `document`, HTML parser, CSSOM, layout engine, `localStorage`, service workers, or navigation model merely because a package probes for browser globals. Those are separate compatibility realms with separate conformance obligations.

## Proposed module order

1. `runtime-core`: owner turns, host ingress, microtasks, Promise core, rejection observation.
2. `runtime-testkit`: deterministic executor, clock, trace corpus, allocation counters.
3. `runtime-android`: `Looper`/`Handler`, monotonic clock, earliest-deadline arming, lifecycle.
4. `web-events`: events and abort algorithms.
5. `web-encoding`: `TextEncoder` and `TextDecoder`.
6. `web-console`: formatting and pluggable sinks.
7. `web-url`: WHATWG URL and search parameters.
8. `web-bodies`: Blob, File, FormData, and body ownership.
9. `web-fetch-core` plus `web-fetch-okhttp`.
10. `websocket-core` plus `websocket-okhttp`.
11. Renderer-specific DOM-shaped types only when a reached program requires them.

Each slice is independently green and includes exact observable tests. API breadth should follow reached programs and compatibility evidence rather than copying an inventory all at once.
