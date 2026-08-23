# 0010 — Keep Android Logcat as a synchronous Console destination

Status: accepted for the first `web-console-android` slice.

## Context

Decision 0009 keeps count state, timer state, group depth, assertion behavior, format-specifier
consumption, and owner confinement in `web-console`. Android still needs a practical destination for
the processed argument lists produced by that capability. `android.util.Log` is a destination, not
the semantic implementation of Console: it does not define JavaScript conversions, count identity,
timer state, groups, or runtime ownership.

The Console Standard requires printing to happen before a Console operation returns. Adding another
queue, executor, `Handler`, logging bridge, Future, coroutine, or callback `Runnable` between the
Console core and Logcat would change that boundary.

## Decision

Add `web-console-android` with:

```text
AndroidLogConsoleSink(String tag)
AndroidLogConsoleSink(String tag, ConsoleValueFormatter printerFormatter)
```

The sink implements `ConsoleSink` directly. The application supplies the Logcat tag and may supply
the same profile formatter used by its Console binding. The default constructor uses
`DefaultConsoleValueFormatter` for ordinary Java values in the current static profile.

The sink consumes the transient processed argument array synchronously, never mutates or retains it,
renders each remaining argument with `formatObject(value, false)`, and joins rendered values with one
space. It represents group depth with two leading spaces per level. Because Logcat has no interactive
group UI, `GROUP_COLLAPSED` receives a stable `[collapsed] ` marker.

Printer levels map to Android priorities as follows:

```text
DEBUG                                      -> Log.d
ASSERT, ERROR                              -> Log.e
WARN, COUNT_RESET, REPORT_WARNING          -> Log.w
INFO, LOG, COUNT, GROUP, GROUP_COLLAPSED,
TIME_LOG, TIME_END                         -> Log.i
```

The mapping is synchronous. The adapter creates no host task, `Runnable`, executor job, `Handler`
callback, Future, coroutine continuation, or secondary logging record.

`ConsoleSink.clear()` is deliberately a no-op. Android exposes no application-scoped operation that
can erase only this Console's prior Logcat records. Clearing process or device logs would exceed the
capability boundary and commonly requires privileges unavailable to applications.

## Ownership and lifecycle

The sink stores only an immutable tag and formatter reference. Runtime owner checks and Console state
remain in `web-console` before the sink is invoked. The application owns tag selection, formatter
selection, Logcat policy, and lifecycle. The adapter holds no Android Context, Looper, Handler,
process-global mutable Console state, or background logger.

Test-only `android.util.Log` primitives live in `web-console-android-testkit` and never enter a
production artifact. Production compiles against the Android API surface and depends on
`web-console`; no Android dependency enters `runtime-core` or the semantic Console module.

## Rejected alternatives

- delegate formatting and state to `android.util.Log`: Logcat is not the Console Standard;
- use `Log.println` or a generic logging bridge: it adds policy without preserving the direct
  level mapping;
- queue records through `Handler`, an executor, Future, or coroutine: this breaks synchronous
  printer ordering;
- mutate or retain the transient argument array: the `ConsoleSink` contract explicitly forbids it;
- invent a process-global default tag or sink: application policy must remain explicit;
- clear Logcat from `console.clear`: there is no safe application-scoped equivalent;
- move counters, timers, or group depth into the Android adapter: those are language-visible
  per-Console state.

## Required evidence

Permanent Java 8 tests and structural gates must prove:

- every distinct `ConsoleLogLevel` maps to the selected Logcat priority;
- publication is visible before `print` returns;
- tags, two-space group indentation, collapsed markers, argument order, and one-space separation;
- a supplied formatter renders each remaining argument and null formatter output is refused;
- the transient argument array is neither mutated nor retained;
- `clear` does not erase prior or unrelated Logcat records;
- null tags, levels, arrays, and formatters plus negative group depths fail deterministically;
- `AndroidLogConsoleSink` implements `ConsoleSink` directly and is not a `Runnable`;
- no `Log.println`, `System.out`, `System.err`, Java Formatter/logging stack, Handler, executor,
  Future, or coroutine path enters `web-console-android`;
- test-only `android.util.Log` doubles remain outside production sources;
- Java 8 compilation with all warnings treated as errors.
