# Working in jvm-www

`jvm-www` is the direct-JVM runtime and Web-capability layer for Native TypeScript. Read `docs/architecture.md` before changing runtime behavior.

## Authority and boundaries

- ECMAScript defines language semantics such as Promise jobs and `async`/`await`.
- WHATWG specifications define selected Web APIs.
- Native TypeScript's checked static profile may reject unsupported shapes precisely.
- React Native is an API inventory and an Android implementation reference, not the semantic authority.
- ScriptC owns checked-IR semantics and JVM lowering. This repository owns the Java runtime ABI, scheduler primitives, capability providers, and conformance fixtures.
- Do not create a second TypeScript parser, AST lowering path, or opaque target-specific language semantics here.

## Non-negotiable runtime rules

- Runtime state is per `RuntimeInstance`; never process-global.
- Only the owner executor may touch TypeScript heap state or run generated TypeScript.
- Foreign threads may publish transport-safe events and request one coalesced wake. They never settle a language Promise or invoke generated code directly.
- Promise reactions and `queueMicrotask` jobs are microtasks. Timers, Android callbacks, Fetch completions, and WebSocket messages are host tasks.
- A complete microtask checkpoint runs after the outermost host turn and before the next host task.
- Do not implement the language Promise with `CompletableFuture`, Kotlin coroutines, executor pools, or one `Runnable` per reaction.
- Do not add a periodic event-loop or timer polling pump.
- Do not silently substitute Node ordering for the default Web Mobile profile.
- Unsupported behavior fails with a stable diagnostic or explicit exception; it is never approximated silently.

## Performance rules

- Core runtime code is Java and compiles against the Java 8 API surface for Android compatibility.
- `runtime-core` has no Android, OkHttp, Kotlin, or general utility dependency.
- Keep hot owner-turn entry/exit paths allocation-free.
- Use one reusable owner wake callback per runtime instance.
- Avoid primitive boxing where checked IR can select a specialized ABI.
- Avoid base64 and repeated byte copies in binary transports unless a compatibility boundary requires them and a test records the cost.
- Every optimization remains subordinate to observable ordering, cancellation, and error semantics.

## Verification

Run before every runtime-core commit:

```sh
./scripts/test-core.sh
```

Ordering changes require falsifying trace tests. Concurrency changes require a deterministic race test plus repeated stress runs. Later compiler integrations must also pass the Native TypeScript and ScriptC gates at their pinned checkpoints.

## Commit discipline

Keep commits small and independently green. Commit messages should state the semantic or performance problem, why the chosen boundary owns it, and the evidence that falsifies likely incorrect alternatives.
