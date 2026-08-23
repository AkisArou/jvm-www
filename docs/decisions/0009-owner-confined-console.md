# 0009 — Keep Console state owner-confined and make printing a synchronous host boundary

Status: accepted for the first `web-console` slice.

## Context

The Console Standard defines observable count maps, timer tables, group stacks, assertion prefixes,
and format-specifier consumption. Its final Printer operation is intentionally implementation-defined.
A direct-JVM target therefore needs a semantic Console object without hard-wiring Android Logcat,
standard output, a background logging executor, or Java object formatting into the Web API state
machine.

The JavaScript conversions required by `%s`, `%d`, `%i`, and `%f` also belong at the checked
compiler/runtime boundary. Calling arbitrary Java `toString` methods cannot silently stand in for
ECMAScript String, parseInt, parseFloat, Symbol handling, or user-defined coercion in every reached
program.

## Decision

Add `web-console` with:

```text
Console
ConsoleLogLevel
ConsoleSink
ConsoleClock
ConsoleValueFormatter
DefaultConsoleValueFormatter
```

Each `Console` belongs to one `RuntimeInstance`. Construction and every operation require the owner
thread during an active host turn or microtask. Count entries, timer entries, and group depth are
instance fields; there is no process-global Console state.

The first reached surface provides:

```text
assertCondition   // JVM ABI for console.assert
clear
debug, error, info, log, warn
count, countReset
group, groupCollapsed, groupEnd
time, timeLog, timeEnd
```

`table`, `trace`, `dir`, and `dirxml` remain unreached. They are not approximated with reflection,
Java stack traces, or arbitrary object dumping.

## Logger and formatter

A logging call with no arguments produces no printer call. With one argument, that value is passed
through unchanged. When a first String has following arguments, the formatter repeatedly consumes
the leftmost supported format specifier. A replacement that introduces another specifier can consume
a later argument, matching the standard's recursive Formatter operation.

The selected first slice recognizes `%s`, `%d`, `%i`, `%f`, `%o`, `%O`, and `%c`. `%c` consumes its
style argument and contributes no text because the non-interactive core defines no CSS presentation.
Unconsumed arguments remain distinct printer arguments.

`ConsoleValueFormatter` is the compiler/profile conversion boundary. Generated bindings may provide
exact ECMAScript String, parseInt, parseFloat, and object-representation operations. The default
formatter is explicitly limited to ordinary Java representations used by the current static profile;
it supplies deterministic primitive, numeric-prefix, and array formatting without claiming dynamic
ToPrimitive or Symbol compatibility.

## Printer boundary

`ConsoleSink.print` is synchronous and receives:

```text
unique ConsoleLogLevel
current group depth
processed transient Object[] arguments
```

The sink must not mutate or retain the array without copying it. Printing happens before the Console
method returns. Sink failures propagate synchronously; the semantic module does not invent an error
reporting or retry policy for a host capability.

`ConsoleSink.DISCARD` keeps Console callable in profiles with no visible developer console.
Platform sinks are separate adapters. `web-console` contains no Android API, Logcat call, stdout or
stderr policy, executor, queue, Future, coroutine, or `Runnable`.

## Counts and groups

Count and timer tables use small intrusive linked entries rather than generic maps that box every
counter or timestamp. `count` increments before printing. `countReset` sets an existing count to zero;
a missing label emits a stable `COUNT_RESET` warning.

A group start is printed at the current depth and then increments the depth. `groupEnd` decrements
when possible and is otherwise a no-op. `clear` resets the group stack and asks the sink to clear its
presentation, but deliberately preserves count and timer tables as required by the Console Standard.

An empty `group` or `groupCollapsed` uses the stable non-interactive label `group`. The distinct
printer level still tells a richer sink whether the group was requested collapsed.

## Timers

`ConsoleClock` supplies monotonic nanoseconds. The default clock delegates to `System.nanoTime`; tests
and platform integrations may supply another monotonic source. Timer state stores the exact start
value and never creates a runtime timer registration or host task.

Durations use the deterministic implementation-defined form:

```text
<label>: <whole milliseconds>.<three fractional digits> ms
```

A duplicate `time` preserves the original start and emits `REPORT_WARNING`. Missing `timeLog` and
`timeEnd` labels also emit stable warnings. `timeEnd` removes its timer before printing the result.

## Assertions

Java cannot name a method `assert`, so the compiler-facing ABI is `assertCondition`. A true condition
returns without printing. A false condition emits `Assertion failed`; when the first data item is a
String, the prefix becomes `Assertion failed: <first>` and then participates in ordinary formatter
processing. A non-String first item is preserved after a newly prepended failure message.

## Rejected alternatives

- one process-global Console singleton: count, timer, group, sink, and lifecycle state would leak
  between `RuntimeInstance` objects;
- `HashMap<String, Long>` for counts and timers: boxes hot primitive state and adds general map
  machinery for typically tiny tables;
- Android Logcat inside `web-console`: makes a platform printer the semantic authority and prevents
  deterministic JVM conformance;
- a logging executor or one `Runnable` per message: changes the standard's synchronous printing
  order and creates a second scheduler;
- unconditional `String.valueOf` for all format conversions: silently approximates ECMAScript
  conversion and Symbol behavior;
- `String.format` or `java.util.Formatter`: imports locale-dependent syntax unrelated to Console
  Standard format specifiers;
- clearing count or timer tables from `clear`: contradicts the specified operation, which empties
  the group stack and optionally clears presentation;
- reflective implementations of `table`, `dir`, or `dirxml`: unsupported shapes must remain explicit.

## Required evidence

Permanent Java 8 tests and structural gates must prove:

- empty logging, unique levels, unformatted argument preservation, recursive specifier consumption,
  object/style handling, and remaining arguments;
- assertion no-op, String-prefix, non-String-prefix, and subsequent formatting behavior;
- independent labels, increment, reset-to-zero, and missing-count warnings;
- group depth, collapsed metadata, unmatched group end, and clear resetting groups only;
- deterministic timer output, duplicate-start preservation, removal by `timeEnd`, and missing-timer
  warnings;
- a supplied `ConsoleValueFormatter` controls conversions in exact consumption order;
- owner-turn confinement and the always-available discard sink;
- no static count/timer state, generic hash map, Java Formatter, Future, coroutine, Android Handler,
  Android Log, or `Runnable` in `web-console`;
- Java 8 compilation with all warnings treated as errors.
