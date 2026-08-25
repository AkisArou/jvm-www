# 0032 — Publish reached client and scroll metrics as owner-confined scalar reads

Status: accepted for the read-only React Native element profile.

## Context

Decision 0030 introduced a compact renderer-owned `ReactNativeElement` wrapper and a shared
`NativeElementContext` whose reusable primitive sink serves the four-value
`getBoundingClientRect()` boundary. Decision 0031 added a Looper-confined Android/native renderer
host with generation-safe identities and one compact committed-state table.

The next reached React Native element properties are:

```text
clientWidth
clientHeight
clientTop
clientLeft
scrollLeft
scrollTop
scrollWidth
scrollHeight
```

The current React Native public element implementation reads inner size, top/left border width,
scroll position, and scroll extent synchronously from its native DOM capability. It returns zero when
no current native element is available. Unlike `offsetWidth` and `offsetHeight`, these properties are
not passed through `Math.round`; fractional values, signed zero, NaN, and infinities remain ordinary
JavaScript numbers if the renderer publishes them.

A Java adapter could model each native result as a `double[]`, a small metrics record, or a callback
into another sink. All three add an allocation or extra multi-value copying to a scalar property
read. Caching a metrics record in `NativeElementContext` would need a renderer revision protocol to
avoid stale values. Returning a sentinel for unavailable state is also invalid because every double,
including NaN and both infinities, is observable data.

## Decision

Extend `NativeElementHost` with eight scalar methods matching the reached properties. Each method
accepts only the opaque primitive element identity and returns the committed primitive value.
Inactive identities and elements without a committed metric snapshot return positive zero.

Extend `ReactNativeElement` and `NativeElementContext` with the same eight reads. The context performs
its existing owner/runtime check and delegates directly to the host. It does not allocate, cache,
round, clamp, normalize, or copy an aggregate.

### Android committed snapshot

Extend the Android host's existing `double[] layout` stride from eight to sixteen values:

```text
0..3   transformed x, y, width, height
4..7   untransformed x, y, width, height
8      clientWidth
9      clientHeight
10     clientTop
11     clientLeft
12     scrollLeft
13     scrollTop
14     scrollWidth
15     scrollHeight
```

One additional state bit records whether the client/scroll half is available. The renderer publishes
all eight metrics through one `commitClientAndScrollMetrics(...)` call. All primitive stores occur
before the state bit is set, and publication and reads are confined to one Looper. A Web read
therefore observes either the previous complete snapshot or the new complete snapshot, never a
partially committed group.

Rectangle and client/scroll availability are independent. Clearing one does not clear the other, and
neither disconnects the element. Unmounting clears all state and advances the generation before the
slot may be reused.

The scalar getter path is:

```text
ReactNativeElement method
  -> NativeElementContext owner check
  -> NativeElementHost scalar method
  -> generation-safe slot resolution
  -> one double-array load or positive zero
```

No second metrics array is introduced. The selected profile has reached all eight values, and keeping
them in the existing per-slot array avoids another array reference, capacity-growth path, and lookup.
It also keeps layout publication data adjacent. The memory cost is eight additional doubles per
allocated table slot.

### Observable values

The compatibility layer preserves the renderer's doubles exactly. It does not apply WebIDL integer
conversion or Java `Math.round` to these selected React Native properties. This is intentionally
different from the already implemented offset dimensions, whose JavaScript implementation explicitly
rounds.

Unavailable, stale, unmounted, and closed-host reads produce positive `0.0`. A committed negative zero
remains negative zero. NaN, infinities, fractions, and negative values are not normalized.

## Performance consequences

Every property read is O(1), performs no allocation, and crosses no callback or task boundary. The
Android path performs one Looper identity check, one generation-safe slot resolution, one state-bit
check, and one primitive array load. There is no tuple, sink, record, boxed number, map lookup, lock,
atomic, Handler post, coroutine, Future, or executor operation.

A metrics commit is eight primitive stores plus one state-bit publication. It allocates nothing.
Capacity growth remains geometric and uses the existing `System.arraycopy` path only when concurrent
mounted-element capacity increases.

## Profile limits

This slice remains read-only. It does not add:

- `scrollLeft`/`scrollTop` setters, `scrollTo`, or `scrollBy`;
- offset-parent, offset-top, or offset-left identity resolution;
- tree traversal or document objects;
- pointer capture, focus, blur, or native commands;
- callback-based legacy measurement;
- text-content traversal; or
- Android `View` or renderer-node retention.

Those operations require command failure, tree revision, wrapper lookup, or asynchronous delivery
contracts and remain separate slices.

## Required evidence

Permanent Java 8 conformance and bytecode gates prove:

- all eight values map to the correct public property and opaque identity;
- no rounding or normalization occurs, including for fractions, signed zero, NaN, infinities, and
  negative values;
- unavailable, stale, unmounted, and closed-host reads return positive zero;
- client/scroll publication is independent from rectangle publication and clearing;
- one commit replaces all eight values and stale commits cannot affect a reused slot;
- owner and Looper confinement reject foreign access before state is touched;
- core property reads allocate no object, array, sink, or boxed number;
- Android metric commits and reads allocate nothing and load directly from the existing primitive
  table; and
- no View, graphics object, map, collection, lock, atomic, Handler, scheduler, reflection path, or
  networking dependency enters production.
