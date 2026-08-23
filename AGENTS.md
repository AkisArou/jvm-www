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
- `web-fetch-okhttp` is replaceable transport plumbing. It accepts an application-owned `Call.Factory`; it does not own client policy, Promise ordering, abort reasons, Headers state, or body-consumption state.
- An OkHttp callback buffers the complete response and closes every live `Response`/`ResponseBody` before publishing a `FetchTransportResponse`. No live OkHttp object crosses into owner-confined language state.
- Do not add a transport executor, Future, coroutine, Handler, URL parser, or synchronous `Call.execute()` path to `web-fetch-okhttp`.
- Do not silently substitute Node ordering for the default Web Mobile profile.
- Unsupported behavior fails with a stable diagnostic or explicit exception; it is never approximated silently.

## Performance rules

- Production runtime code is Java and compiles against the Java 8 API surface for Android compatibility.
- `runtime-core` has no Android, OkHttp, Kotlin, or general utility dependency.
- `runtime-android` depends only on `runtime-core` and the Android API surface; test-only `android.os` stubs stay in `runtime-android-testkit` and must never enter a production artifact.
- `web-fetch-core` has no OkHttp dependency. Test-only `okhttp3` doubles stay in `web-fetch-okhttp-testkit` and must never enter a production artifact.
- Keep hot owner-turn entry/exit paths allocation-free.
- Use one reusable owner wake callback per runtime instance.
- Avoid primitive boxing where checked IR can select a specialized ABI.
- Preserve the accepted fused async-frame ABI unless a new decision record supplies contrary allocation and lifetime evidence.
- A platform operation's common completion object is the returned Promise, foreign first-completion token, and admitted `RuntimeTask`. Do not add a resolver/future/task wrapper layer.
- Platform completion publication uses the object's monitor fast path rather than allocating one atomic holder per operation; do not replace it with reflection-sensitive field updaters without R8 evidence.
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
./scripts/test-fetch.sh
./scripts/test-fetch-okhttp.sh
```

The gates compile production and deterministic testkit sources against Java 8. Ordering changes require falsifying trace tests. Concurrency changes require a deterministic race test plus repeated stress runs. Platform-completion changes require first-completion, completion-versus-close, losing-reference disposal, failed-owner-post, and separate-runtime isolation tests. Android-host changes require structural evidence for uptime/`postAtTime`, direct callback posting, and the absence of another scheduler. OkHttp transport changes require request-snapshot mapping, callback-thread buffering and closure, exact-call cancellation, failure disposal, owner-delivery, and structural no-scheduler evidence. Later compiler integrations must also pass the Native TypeScript and ScriptC gates at their pinned checkpoints.

## Commit discipline

All work is committed and pushed directly to `main`. Do not create topic or work branches in this repository. Multiple agents may advance `main`, so re-read its head immediately before creating a commit, build on that exact tree, use a non-forced fast-forward update, and rebase/reconstruct the commit if the head changed.

Keep commits small and independently green. Commit messages should state the semantic or performance problem, why the chosen boundary owns it, and the evidence that falsifies likely incorrect alternatives.

When a change establishes a cross-agent architectural contract, add or update a decision record in the same commit. The repository—not a conversation transcript—is the source other agents should consume.
