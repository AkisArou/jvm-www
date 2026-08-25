# ADR 0037: Keep JVM backend primitives and platform hosts in `jvm-www`

- Status: Accepted
- Date: 2026-08-25
- Owners: ScriptC JVM backend, Native TypeScript, Android host integration

## Context

ScriptC already provides TypeScript-to-C/LLVM lowering and a native runtime with Promise, `async`/`await`, microtasks, timers, Fetch, Node-compatible APIs, and other JavaScript-like facilities. Native TypeScript originally reused that C path on Android through JNI. That avoided reimplementing language/runtime behavior, but it placed a high-frequency boundary between generated application code and the native runtime.

The Java backend exists to remove that boundary for ordinary Android execution. Generated TypeScript should execute as Java/DEX on ART, call JVM runtime primitives directly, and use Android platform APIs without routing Promise reactions, `await` resumes, timers, networking state machines, renderer traversal, or ordinary host calls through JNI.

At the same time, maintaining independent Java and C implementations for every portable Web or renderer API would duplicate semantics and create drift. ScriptC is moving toward multiple code-generation targets, so portable behavior should increasingly have one shared TypeScript implementation compiled to each target. Low-level runtime machinery and platform capabilities remain target-specific.

This record defines the permanent ownership boundary for `jvm-www` and the migration direction for its existing capability modules.

## Decision

`jvm-www` is the hand-written JVM runtime and Android platform-host implementation for ScriptC-generated Java.

It permanently owns code that is inherently JVM/backend-specific or Android/platform-specific:

- JVM Promise storage, settlement, reaction queues, and unboxed payload ABI;
- compiler-facing JVM `async`/`await` continuation-frame ABI;
- microtask checkpoints and host-task admission;
- runtime ownership, shutdown, and active-resource cancellation;
- the JVM logical timer kernel where direct runtime integration is materially more efficient than generated library code;
- Android `Looper`/`Handler` ownership, owner wakes, and timer alarms;
- Android display-frame and idle callbacks;
- Android/JVM transport adapters such as OkHttp Fetch and WebSocket plumbing;
- Android logging sinks;
- the future Android UI host that owns actual `View`/`ViewGroup`/text/input/scroll platform objects and imperative platform operations;
- selected JVM intrinsics only when profiling shows generated shared TypeScript cannot meet the required cost;
- JVM/Android conformance, differential, lifecycle, and structural performance gates.

Portable behavior should not have Java as its final semantic source merely because the JVM backend exists. Portable Web APIs, value types, renderer tree semantics, and React-facing public-instance behavior should move to shared TypeScript and compile through ScriptC to Java on Android and C/ObjC++ on iOS/native targets.

## Backend-neutral language semantics

Promise and `async`/`await` are not ordinary library APIs. They are compiler/runtime primitives with one ScriptC semantic contract and target-specific execution mechanisms.

The intended split is:

```text
TypeScript
    |
ScriptC frontend / checked IR
    |
    +----------------------+----------------------+
    |                                             |
C / LLVM backend                              JVM backend
    |                                             |
C Promise + stackful fibers                  Java Promise + resumable frames
native event loop                            RuntimeInstance + host integration
```

The C backend may continue to implement suspension with stackful fibers. The JVM backend should lower the same observable semantics to compact generated state machines using `AsyncFrame` or its successor. Java threads, `CompletableFuture`, Kotlin coroutines, and JNI calls per Promise/await operation are not the target model.

A backend-neutral runtime ABI must eventually define the operations the compiler depends on, including Promise creation/settlement/adoption, await suspension/resumption, microtask admission, host-task admission, timer arming/cancellation, exception transport, and runtime shutdown. The C and JVM runtimes may use different storage and scheduling mechanisms while satisfying the same observable contract.

## JNI policy

JNI remains allowed for coarse native capabilities or proven acceleration boundaries, for example:

- one Yoga/layout operation over a subtree;
- image decode;
- a cryptographic primitive;
- SQLite or another native database call;
- a large compression or graphics operation.

JNI is not the ordinary ABI for:

- `Promise.then` or settlement;
- `async`/`await` suspension and resume;
- microtask queue operations;
- timers;
- Fetch/WebSocket state transitions;
- React renderer tree traversal;
- element/ref getters;
- high-frequency UI property traffic.

## Portable semantics that should migrate to shared TypeScript

The following current Java capability implementations are valuable reference implementations and conformance sources, but are not their intended final semantic home:

- `Event`, `CustomEvent`, `EventTarget`, `DOMException`, `AbortController`, `AbortSignal`;
- `TextEncoder`, `TextDecoder`, Base64 utilities, subject to optional proven intrinsics;
- Console formatting/count/timer/group semantics;
- `URLSearchParams` and WHATWG `URL` semantics;
- `Blob`, `File`, `FormData`, body state, multipart behavior;
- `Headers`, `Request`, `Response`, and the Fetch state machine;
- WebSocket state/event/protocol semantics;
- `DOMPoint`, `DOMRect`, `DOMQuad`, `DOMMatrixReadOnly`, `DOMMatrix`;
- stable renderer element identities, public ref wrappers, tree relations, `HTMLCollection`, containment, and other portable renderer/DOM-like semantics.

Generated implementations may call small JVM intrinsics such as exact byte copying, UTF-8 kernels, or matrix kernels when benchmarks justify them. An intrinsic accelerates one shared semantic definition; it does not create an independent Java behavior contract.

## Current module disposition

| Current module | Long-term disposition |
| --- | --- |
| `runtime-core` | **Keep.** Becomes the JVM implementation of the ScriptC runtime ABI. |
| `runtime-testkit` | **Keep.** Expand into JVM and cross-backend differential conformance. |
| `runtime-android` | **Keep.** Android owner/timer host only. |
| `runtime-android-testkit` | **Keep.** Android runtime-host integration tests. |
| `web-events` | **Migrate to shared TypeScript.** Retain only runtime hooks that prove necessary. |
| `web-events-testkit` | **Migrate/reuse tests across backends.** |
| `web-encoding` | **Migrate to shared TypeScript or a compiler/runtime intrinsic.** |
| `web-encoding-testkit` | **Reuse as cross-backend conformance.** |
| `web-base64` | **Migrate to shared TypeScript.** |
| `web-base64-testkit` | **Reuse as cross-backend conformance.** |
| `web-console` | **Migrate semantic state/formatting to shared TypeScript.** |
| `web-console-testkit` | **Reuse semantic tests across backends.** |
| `web-console-android` | **Keep.** Android Log sink. |
| `web-console-android-testkit` | **Keep.** Android sink integration. |
| `web-url` | **Migrate to shared TypeScript.** |
| `web-url-testkit` | **Reuse as cross-backend conformance.** |
| `web-bodies` | **Migrate to shared TypeScript.** |
| `web-bodies-testkit` | **Reuse as cross-backend conformance.** |
| `web-fetch-core` | **Split.** Move Web semantics to TypeScript; retain only the minimal JVM transport/runtime ABI that remains necessary. |
| `web-fetch-testkit` | **Migrate semantic tests to cross-backend conformance.** |
| `web-fetch-okhttp` | **Keep.** Android/JVM HTTP transport. |
| `web-fetch-okhttp-testkit` | **Keep.** Transport/resource integration tests. |
| `web-timing` | **Split.** Portable Web API semantics move to TypeScript; efficient JVM scheduling/runtime primitives stay where required. |
| `web-timing-testkit` | **Split tests between shared semantics and JVM scheduler conformance.** |
| `web-timing-android` | **Keep.** Android frame/idle host integration. |
| `web-timing-android-testkit` | **Keep.** Android timing integration tests. |
| `web-geometry` | **Migrate to shared TypeScript.** JVM matrix/byte intrinsics remain optional optimization points. |
| `web-geometry-testkit` | **Reuse randomized/reference tests across backends.** |
| `web-native-elements` | **Migrate to the shared TypeScript renderer.** |
| `web-native-elements-testkit` | **Reuse renderer/ref semantic tests across backends.** |
| `web-native-elements-android` | **Shrink/replace.** Final form is a narrow Android UI/measurement/focus/scroll/event host, not a duplicate renderer tree. |
| `web-native-elements-android-testkit` | **Rework around the Android UI host.** |
| `websocket-core` | **Migrate to shared TypeScript.** |
| `websocket-testkit` | **Reuse semantic tests across backends.** |
| `websocket-okhttp` | **Keep.** Android/JVM WebSocket transport. |
| `websocket-okhttp-testkit` | **Keep.** Transport integration tests. |

No current portable Java module is deleted merely because this direction is accepted. Migration happens only after the shared TypeScript implementation compiles through the relevant ScriptC backends and passes equivalent conformance.

## Renderer ownership

The future React renderer should be shared TypeScript compiled by ScriptC. It should own:

- the shadow/tree model;
- stable/generation-safe renderer identities;
- public element/ref identity;
- parent/child/sibling relationships;
- layout snapshots and renderer-level geometry state;
- portable event propagation and renderer semantics;
- React reconciler HostConfig and commit coordination.

The Android host should own only actual Android platform integration, for example:

```text
mountView
updateView
unmountView
measureView
focusView
blurView
scrollView
subscribeNativeEvents
```

The current Android native-element committed table remains useful as a proven prototype and reference for stale-generation safety and allocation behavior, but it should not become a second permanent renderer tree beside the shared renderer.

## Completion status of retained JVM/Android pieces

The retained pieces are not all finished. Their current status is:

| Retained area | Status | What remains before calling it finished |
| --- | --- | --- |
| JVM Promise core | **Mature foundation, not finished** | Pin a backend-neutral ScriptC Promise ABI; add any checked-IR Promise shapes required by real ScriptC lowering, including dynamic thenable handling only if the selected profile admits it; add missing combinators only when required by the shared runtime/library profile. |
| JVM `async`/`await` frame runtime | **Mature foundation, not integrated end-to-end** | Implement/pin ScriptC JVM lowering that generates these frames, then differential-test real compiled async programs against Node and the C backend. |
| Microtask/host-task runtime | **Mature foundation** | Validate through compiler-generated programs and sustained mixed timer/network/UI workloads; keep one semantic ordering contract across C and JVM. |
| JVM logical timers | **Mature foundation** | Integrate with final compiler/runtime ABI and shared TypeScript timer surface; add real-device lifecycle/Doze/background policy tests where the mobile profile requires them. |
| Android `Looper`/`Handler` host | **Close for its narrow contract** | Real Android instrumentation, lifecycle attachment, packaging integration, and R8/ART verification. |
| Android frame/idle hosts | **Close for their narrow contracts** | Wire them to the final shared timing API and verify on devices/API levels used by the product. |
| Active-resource shutdown registry | **Mature foundation** | Exercise all final retained host resources through one backend ABI and compiler integration suite. |
| OkHttp Fetch transport | **Finished for the current buffered transport profile; not final overall** | Refactor behind the future shared-TS Fetch host ABI; add streaming request/response transport support only if the selected profile requires it. |
| OkHttp WebSocket transport | **Finished for the current transport profile; not final overall** | Refactor behind the future shared-TS WebSocket host ABI and validate final backpressure/lifecycle requirements. |
| Android console sink | **Essentially finished for its sink contract** | Adapt to the final shared Console host interface and run device integration. |
| Android UI host | **TODO** | This is a major missing retained subsystem: actual Android view creation/update/unmount, measurement, focus, scrolling, accessibility, and native event ingress. |
| JVM intrinsics layer | **TODO / ad hoc today** | Define a formal intrinsic mechanism only for measured hot paths; do not preemptively native-optimize portable semantics. |
| Differential test system | **Partial** | Establish the permanent `Node == ScriptC C/LLVM == ScriptC JVM` lane for shared semantics and compiler/runtime traces. |
| End-to-end ScriptC JVM backend integration | **TODO / highest priority** | Define the backend-neutral runtime ABI and make generated Java call `runtime-core` directly without JNI for ordinary language/runtime work. |

Therefore the next priority is not to rewrite Promise again. It is to connect the existing JVM runtime foundation to ScriptC's JVM lowering and prove semantic equivalence through compiled programs.

## Migration sequence

1. Keep the current `jvm-www` tree green and usable while the JVM compiler backend matures.
2. Define and version the backend-neutral ScriptC runtime ABI.
3. Make the JVM backend lower Promise/`async`/`await`, microtasks, timers, exceptions, and host admission directly onto `runtime-core`.
4. Establish three-way differential conformance: Node/reference JavaScript engine, ScriptC C/LLVM, and ScriptC JVM.
5. Move portable Web APIs one by one to shared TypeScript.
6. Remove/demote each Java semantic duplicate only after the shared implementation passes equivalent C/JVM/reference tests.
7. Build the shared TypeScript React renderer.
8. Replace the duplicated Android native-element renderer state with a narrow Android UI host.
9. Add JVM intrinsics only where measurements justify them.

## Consequences

This keeps JNI out of the high-frequency language and renderer path while avoiding a permanent fork of portable semantics. C/LLVM and JVM backends are free to use mechanisms appropriate to their targets, but both satisfy one ScriptC language/runtime contract. Shared TypeScript becomes the canonical home for portable libraries and renderer behavior, while `jvm-www` remains focused on the parts that genuinely benefit from or require hand-written JVM/Android code.
