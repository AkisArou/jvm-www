# 0033 — Resolve native-element offsets through generation-safe public wrappers

Status: accepted for the reached React Native `offsetParent`, `offsetTop`, and `offsetLeft` surface.

## Context

The renderer-facing element profile already exposes transformed bounds, untransformed offset size,
client metrics, and scroll metrics. React Native also exposes the `HTMLElement`-shaped properties
`offsetParent`, `offsetTop`, and `offsetLeft`.

React Native's current implementation obtains one native offset snapshot containing:

```text
offset parent instance handle
top relative to that parent
left relative to that parent
```

All React Native elements are currently treated as positioned, so the offset parent is the current
parent element. A child of the native root still has usable top and left offsets, but the root itself
is not exposed as a JavaScript element and `offsetParent` is therefore `null`. Disconnected,
undisplayed, or otherwise unavailable elements observe `null`, `0`, and `0`. Top and left use
ECMAScript `Math.round`, including negative zero and ties toward positive infinity.

Returning the parent introduces an identity problem that scalar metrics did not have. A renderer slot
may be reused after unmount, but an old child offset snapshot must never resolve to the wrapper for the
new occupant. The returned parent must also be the exact stable public wrapper already exposed through
the renderer, not a newly allocated equivalent wrapper.

A core-owned `Map<Long, ReactNativeElement>` would duplicate renderer lifetime state, box identities,
add lookups to every mounted component, and create a second stale-entry policy. Returning a temporary
offset tuple or callback object would add allocation to all three getters.

## Decision

Add two narrow production interfaces:

```text
NativeElementOffsetSink
NativeElementPublicInstanceStore
```

`NativeElementHost` extends `NativeElementPublicInstanceStore` and adds:

```java
boolean measureOffset(
        long elementIdentity,
        NativeElementOffsetSink sink);
```

A successful call writes exactly once:

```java
sink.setOffset(
        hasOffsetParent,
        offsetParentIdentity,
        top,
        left);
```

The boolean is explicit because the transport-independent core treats renderer identities as opaque;
no long value is reserved globally as a null sentinel. A false host result writes nothing.

### One context, two reusable sinks

`NativeElementContext` directly implements both rectangle and offset sinks. It owns primitive scratch
fields and one shared read-kind state:

```text
READ_NONE
READ_RECT
READ_OFFSET
```

The shared state rejects rectangle-to-offset, offset-to-rectangle, and same-kind reentry. It also
rejects use of either retained sink after the synchronous host call, missing writes, false-after-write,
and duplicate writes. No per-read adapter or result object is created.

`offsetTop` and `offsetLeft` pass the raw host values through the same explicit ECMAScript rounding
kernel used by offset dimensions. `offsetParent` asks the renderer-owned public-instance store for the
published wrapper and returns that exact object. An unavailable offset, a root parent, a stale parent,
or a parent without a published wrapper produces `null`.

### Renderer-owned stable public instances

The store contract is:

```java
ReactNativeElement getPublicInstance(long identity);

ReactNativeElement registerPublicInstance(
        long identity,
        ReactNativeElement instance);
```

`NativeElementContext.createElement(identity)` first checks the renderer store. A cache hit returns the
existing wrapper without allocation. A cache miss allocates exactly one required `ReactNativeElement`,
registers it, and verifies that the renderer retained that exact object.

The context validates that every wrapper returned by the store belongs to the same context and exact
identity. This prevents cross-runtime or cross-identity wrapper leakage even when a host implementation
violates its contract.

The public wrapper remains exactly:

```text
NativeElementContext context
long elementIdentity
```

No cache, runtime reference, parent field, offset field, or lifecycle slot is added per wrapper.

### Android committed offset table

`AndroidNativeElementHost` remains one Looper-confined table and adds:

```text
long[] offsetParents
ReactNativeElement[] publicInstances
```

Two additional values in the existing primitive layout array store unrounded top and left. One state
bit marks an available offset snapshot.

Android renderer identities are positive, so `commitOffset(identity, 0, top, left)` uses zero only
inside this adapter to represent the inaccessible native root. A nonzero parent must resolve to a
distinct currently mounted exact identity before publication.

A successful read performs:

```text
Looper check
exact child slot resolution
offset availability check
exact parent-generation validation
one long load and two double loads
one direct sink call
```

If a committed parent is later unmounted, resolving the child offset fails instead of allowing the old
parent identity to alias a new slot generation. Recommitting against the replacement identity makes
the relationship available again.

Unmount clears the public-wrapper reference and parent identity before placing the slot on the free
list. The externally retained old wrapper remains disconnected. Reuse creates and publishes a new
wrapper under the incremented generation.

## Performance consequences

- A repeated wrapper lookup is O(1) in the Android table and allocates nothing.
- The first wrapper creation allocates only the required public wrapper.
- `offsetParent`, `offsetTop`, and `offsetLeft` allocate no tuple, array, callback, sink, boxed identity,
  or replacement wrapper.
- Android offset commit and read are allocation-free primitive/reference-array operations.
- Table growth remains proportional to peak simultaneous mounted elements and uses
  `System.arraycopy`.
- No core map, Android sparse array, lock, atomic, Handler post, executor, Future, coroutine, or
  reflection path is introduced.

The additional Android per-slot storage is one long parent identity and one public-wrapper reference,
plus two doubles in the existing layout array. The public wrapper is already required by the renderer;
the table adds only the reference needed to preserve JavaScript object identity.

## Profile limits

- The offset parent is a renderer-published direct parent, matching React Native's current positioned
  element model. General browser CSS containing-block discovery is not implemented.
- The native root remains inaccessible as an element and therefore maps to a null offset parent.
- General `parentNode`, siblings, children, documents, and tree collections require a broader
  renderer-revision contract and remain separate slices.
- Focus, blur, pointer capture, scrolling commands, and callback-based legacy measurement remain
  imperative or asynchronous capabilities with separate lifecycle rules.
- The Android adapter stores committed renderer data; it does not discover offsets by retaining or
  traversing `android.view.View` instances.

## Required evidence

Permanent Java 8 conformance and bytecode gates prove:

- exact parent wrapper identity and cache-hit reuse;
- root-parent null behavior with still-available top and left;
- unavailable and disconnected defaults;
- ECMAScript rounding for fractions, ties, negative zero, NaN, and infinities;
- generation-safe parent invalidation and non-aliasing after slot reuse;
- cross-context public-wrapper rejection;
- sink lifetime, write-count, exception, and cross-kind reentry recovery;
- one context directly implementing both reusable sinks;
- one reference plus one long in every public wrapper;
- no allocation in offset getters, Android offset publication, Android offset reads, or public-instance
  lookup/registration;
- no generic collection or boxed identity in production; and
- no Android View, graphics, Handler, concurrency, scheduler, reflection, or network dependency.
