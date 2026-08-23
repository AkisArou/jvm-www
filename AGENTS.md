# Working in jvm-www

`jvm-www` is the direct-JVM runtime and Web-capability layer for Native TypeScript. Read `docs/architecture.md` and the applicable records under `docs/decisions/` before changing runtime behavior. Decision records are the shared handoff between compiler, runtime, Android-host, and capability work; do not rely on private chat context.

## Authority and boundaries

- ECMAScript defines language semantics such as Promise jobs and `async`/`await`.
- WHATWG specifications define selected Web APIs.
- Native TypeScript's checked static profile may reject unsupported shapes precisely.
- React Native is an API inventory and an Android implementation reference, not the semantic authority.
- ScriptC owns checked-IR semantics and JVM lowering. This repository owns the Java runtime ABI, scheduler primitives, Android host adapter, capability providers, and conformance fixtures.
- Do not create a second TypeScript parser, AST lowering path, or opaque target-specific language semantics here.

## Non-negotiable runtime rules

- Runtime state is per `RuntimeInstance`; never process-global.
- Only the owner executor may touch TypeScript heap state or run generated TypeScript.
- Foreign threads may publish transport-safe events and request one coalesced wake. They never settle a language Promise or invoke generated code directly.
- Platform operations use `PlatformPromise` or an equivalent owner-admission ABI. A worker callback may only claim a first completion, store a copied/retained payload, and admit a host task.
- Promise reactions and `queueMicrotask` jobs are microtasks. Timers, Android callbacks, Fetch completions, and WebSocket messages are host tasks.
- A complete microtask checkpoint runs after the outermost host turn and before the next host task.
- Do not implement the language Promise with `CompletableFuture`, Kotlin coroutines, executor pools, or one `Runnable` per reaction.
- Logical timers use one per-runtime deadline heap and at most one armed platform callback. Do not add `ScheduledExecutorService`, one `Runnable` per timer, fixed-rate interval catch-up, or a periodic timer pump.
- `runtime-android` is a host adapter, not a semantic runtime. It must not contain Promise settlement, timer coercion, heap ordering, interval identity, or microtask policy.
- The Handler timer alarm uses `SystemClock.uptimeMillis` and absolute `Handler.postAtTime` with upward rounding. Do not substitute wall time, `System.nanoTime`, elapsed realtime, or an independently sampled `postDelayed` delay.
- Android posts the reusable callbacks supplied by `runtime-core` directly. Do not add an adapter-owned callback wrapper per wake or alarm.
- A failed `OwnerExecutor.post` must mean the callback was not enqueued. The runtime removes and discards the exact admission whose wake could not be published.
- Reference payload ownership must be explicit. Losing, discarded, or owner-overridden platform completions release retained references exactly once without executing TypeScript.
- `TextEncoder` and `TextDecoder` are owner-confined Web objects. Decoder streaming and BOM state is per object and may not be cached process-globally or moved to a worker.
- The selected UTF-8 implementation owns scalar conversion, malformed-sequence grouping, fatal errors, BOM handling, and `encodeInto` progress. Do not substitute `String.getBytes`, `new String(bytes, charset)`, or a retained JDK charset wrapper.
- Unreached encoding labels fail with `JsRangeError`; never accept a JVM alias as an approximation of a WHATWG label.
- Each `Console` owns its count table, timer table, and group depth. Never make Console state process-global or share it across `RuntimeInstance` objects.
- Console printing is synchronous through `ConsoleSink`. Do not add a message queue, logging executor, Future, coroutine, or one `Runnable` per log call.
- `ConsoleValueFormatter` is the compiler/profile boundary for ECMAScript conversions. Do not silently replace dynamic String, parseInt, parseFloat, Symbol, or ToPrimitive semantics with Java `toString` behavior.
- `console.clear` resets presentation and the group stack only. It must not clear count or timer state.
- `web-fetch-okhttp` is replaceable transport plumbing. It accepts an application-owned `Call.Factory`; it does not own client policy, Promise ordering, abort reasons, Headers state, or body-consumption state.
- An OkHttp callback buffers the complete response and closes every live `Response`/`ResponseBody` before publishing a `FetchTransportResponse`. No live OkHttp object crosses into owner-confined language state.
- Do not add a transport executor, Future, coroutine, Handler, URL parser, or synchronous `Call.execute()` path to `web-fetch-okhttp`.
- Do not silently substitute Node ordering for the default Web Mobile profile.
- Unsupported behavior fails with a stable diagnostic or explicit exception; it is never approximated silently.

## Performance rules

- Production runtime code is Java and compiles against the Java 8 API surface for Android compatibility.
- `runtime-core` has no Android, OkHttp, Kotlin, or general utility dependency.
- `runtime-android` depends only on `runtime-core` and the Android API surface; test-only `android.os` stubs stay in `runtime-android-testkit` and must never enter a production artifact.
- `web-encoding` contains a compact UTF-8 state machine and no `java.nio.charset`, Android, scheduler, or transport dependency.
- `web-console` contains no Android Log, stdout/stderr policy, executor, scheduler, or platform dependency. Platform printers remain separate sink adapters.
- `web-fetch-core` has no OkHttp dependency. Test-only `okhttp3` doubles stay in `web-fetch-okhttp-testkit` and must never enter a production artifact.
- Keep hot owner-turn entry/exit paths allocation-free.
- Use one reusable owner wake callback per runtime instance.
- Avoid primitive boxing where checked IR can select a specialized ABI.
- Preserve the accepted fused async-frame ABI unless a new decision record supplies contrary allocation and lifetime evidence.
- A platform operation's common completion object is the returned Promise, foreign first-completion token, and admitted `RuntimeTask`. Do not add a resolver/future/task wrapper layer.
- Platform completion publication uses the object's monitor fast path rather than allocating one atomic holder per operation; do not replace it with reflection-sensitive field updaters without R8 evidence.
- `TextEncoder.encode` writes one exactly sized array. `encodeInto` writes directly into its destination and never creates a temporary encoded array.
- A streaming `TextDecoder` retains only compact scalar/continuation/BOM state; it does not retain caller byte arrays between calls.
- Console count and timer tables use small intrusive entries rather than generic maps that box every value. The sink must not retain or mutate transient argument arrays without copying them.
- Console timing reads a monotonic `ConsoleClock`; it never registers a runtime timer or host task.
- The OkHttp adapter's common bridge object is both `okhttp3.Callback` and `FetchTransportCall`; do not add a second callback/cancellation wrapper or a callback `Runnable`.
- Timer slots and heap entries may be reused, but never at the cost of stale-handle safety or callback ordering.
- Avoid base64 and repeated byte copies in binary transports unless a compatibility boundary requires them and a test records the cost.
- Every optimization remains subordinate to observable ordering, cancellation, ownership, and error semantics.

## Verification

Run before every runtime or Android-host commit:

```sh
./scripts/test-core.sh
```

Run the applicable permanent Web gates before capability or transport commits:

```sh
./scripts/test-encoding.sh
./scripts/test-console.sh
./scripts/test-fetch.sh
./scripts/test-fetch-okhttp.sh
```

The gates compile production and deterministic testkit sources against Java 8. Ordering changes require falsifying trace tests. Concurrency changes require a deterministic race test plus repeated stress runs. Platform-completion changes require first-completion, completion-versus-close, losing-reference disposal, failed-owner-post, and separate-runtime isolation tests. Android-host changes require structural evidence for uptime/`postAtTime`, direct callback posting, and the absence of another scheduler. Encoding changes require scalar/surrogate traces, malformed UTF-8 boundaries, fatal and replacement paths, split streaming sequences, BOM state, `encodeInto` progress, Fetch integration, and structural exclusion of JVM charset convenience APIs. Console changes require logger/formatter traces, assertions, independent counts, group depth, clear semantics, monotonic timing, warning paths, supplied conversion boundaries, owner confinement, and structural exclusion of global maps, Java Formatter, schedulers, Android Log, and Runnable jobs. OkHttp transport changes require request-snapshot mapping, callback-thread buffering and closure, exact-call cancellation, failure disposal, owner-delivery, and structural no-scheduler evidence. Later compiler integrations must also pass the Native TypeScript and ScriptC gates at their pinned checkpoints.

## Commit discipline

All work is committed and pushed directly to `main`. Do not create topic or work branches in this repository. Multiple agents may advance `main`, so re-read its head immediately before creating a commit, build on that exact tree, use a non-forced fast-forward update, and rebase/reconstruct the commit if the head changed.

Keep commits small and independently green. Commit messages should state the semantic or performance problem, why the chosen boundary owns it, and the evidence that falsifies likely incorrect alternatives.

When a change establishes a cross-agent architectural contract, add or update a decision record in the same commit. The repository—not a conversation transcript—is the source other agents should consume.
