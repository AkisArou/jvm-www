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

The Promise core and the compiler-facing `AsyncFrame` ABI are now implemented
independently of that unavailable emitter checkpoint. The accepted lowering
contract is recorded in:

```text
docs/promise-runtime.md
docs/decisions/0001-fused-async-frame.md
```

A future ScriptC integration must consume checked IR and generate subclasses of
`AsyncFrame`; it must not reinterpret TypeScript AST independently. The emitter
may adapt exact method names as the reachable IR requires, but any incompatible
change to the fused result-Promise/frame/resume-job decision must update the
decision record and its conformance evidence rather than silently introducing a
parallel ABI.
