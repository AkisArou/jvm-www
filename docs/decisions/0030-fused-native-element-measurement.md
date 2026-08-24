# 0030 — Fuse the native-element context with one primitive measurement sink

Status: accepted for the first renderer-facing React Native element profile.

## Context

The Web Mobile profile now has primitive Geometry Interfaces values and a renderer needs an object
that a native-component ref can expose. Current React Native element nodes provide synchronous
`getBoundingClientRect()`, `offsetWidth`, `offsetHeight`, identity metadata, and connection state.
The public bounding rectangle is transformed, while offset dimensions are sampled without transforms
and rounded. Detached or unavailable nodes observe empty metadata and zero layout.

This boundary is a hot path: one wrapper may exist for every rendered host component and layout reads
can occur during `useLayoutEffect`. Representing the native identity with an Android `View`, renderer
shadow node, generic Java object map, or JNI handle in the public object would couple Web semantics to
one renderer and complicate lifecycle safety. Returning a Java `double[]` from every measurement
would add a temporary tuple allocation before the required `DOMRect` result. Passing a newly allocated
callback object to the renderer would be worse.

The first reached slice does not need tree traversal, documents, text nodes, mutation, focus, pointer
capture, scrolling, legacy callback measurement, or an Android view adapter. Those operations have
separate ordering, ownership, and lifecycle contracts.

## Decision

Add:

```text
web-native-elements
web-native-elements-testkit
```

The production module exposes:

```text
NativeElementRectSink
NativeElementHost
NativeElementContext
ReactNativeElement
```

### Opaque renderer identity and wrapper ownership

Each `ReactNativeElement` stores exactly:

```text
NativeElementContext context
long elementIdentity
```

The identity is opaque to the Web layer. The renderer creates and caches one wrapper for each public
native-component ref. If renderer slots are reusable, the long identity must encode a generation; an
old wrapper may never silently begin referring to a newer element.

The compatibility layer intentionally does not add a wrapper cache. The renderer already owns public
instance identity and native-node lifetime, and duplicating that registry here would add a map lookup,
retention policy, and another source of stale identity.

### One shared context and one reusable sink

One `NativeElementContext` is shared by every wrapper belonging to a renderer/runtime pair. It stores:

```text
RuntimeInstance runtime
NativeElementHost host
four primitive measurement fields
measurement-active and measurement-written booleans
```

The context itself implements `NativeElementRectSink`. A host measurement therefore receives the
same object on every call; there is no callback, tuple, array, result carrier, or boxed coordinate
allocated at the host boundary.

`NativeElementHost.measureBoundingClientRect(identity, includeTransform, sink)` is synchronous. A
`true` result must write exactly one rectangle before returning. A `false` result must not write. The
context rejects missing writes, writes paired with false, duplicate writes, and measurement reentry,
and restores reusable state after a host exception.

The host is trusted not to retain the sink or invoke generated language code. A future Android adapter
will translate a renderer-owned identity to the current committed layout snapshot without exposing an
`android.view.View` through this module.

### Selected observable surface

The first `ReactNativeElement` surface is:

```text
isConnected
id
tagName / nodeName
nodeType = ELEMENT_NODE
nodeValue = null
getBoundingClientRect
offsetWidth
offsetHeight
```

Java construction is renderer-only through `NativeElementContext.createElement`. The generated Web
profile does not expose an imperative element constructor.

`getBoundingClientRect()` requests `includeTransform = true`. Whether the host has a current layout or
not, the method allocates exactly one new `DOMRect`, making every result a static snapshot. An
unavailable element uses the context's reset zero fields.

`offsetWidth` and `offsetHeight` request `includeTransform = false`, read only the relevant primitive
field, and allocate nothing. Rounding follows ECMAScript `Math.round`, including NaN, infinities, and
negative zero, rather than Java's integral `Math.round` carrier.

Null tag or ID values from a renderer normalize to the Web empty string. Geometry coordinates remain
`unrestricted double` values and are not clamped or normalized by this layer.

### Owner confinement

Context construction, wrapper creation, metadata reads, connection checks, and measurements require
the `RuntimeInstance` owner while an active host turn or microtask is executing. Rejected idle,
foreign-thread, or closed-runtime access occurs before the renderer host is touched.

Geometry results themselves remain ordinary synchronous primitive-backed values with no runtime
owner, as established by decision 0026.

## Performance consequences

A wrapper is one object with one reference and one primitive long. A context is one shared object per
renderer/runtime pair. A successful or unavailable bounding-rectangle read performs one synchronous
host call and allocates only the required returned `DOMRect`. Offset reads perform no allocation.
Metadata and connection reads delegate directly through the opaque primitive identity.

There is no per-element runtime reference duplication, native `View` retention, coordinate array,
map, list, lock, atomic, Future, coroutine, executor, Android callback, reflection path, or JNI
transition in the selected layer.

## Profile limits

- Parent/child/sibling traversal and document nodes require a stable renderer tree-revision and public
  instance lookup contract.
- `textContent` requires a bounded renderer traversal policy.
- Focus, blur, pointer capture, and scrolling are imperative platform commands and need their own
  failure and lifecycle behavior.
- Legacy `measure`, `measureInWindow`, and `measureLayout` are asynchronous callback APIs; they are
  not aliases for this synchronous committed-layout snapshot.
- Android wiring remains a replaceable renderer adapter and must not move `View` ownership into the
  Web object.
- A general DOM, mutation API, CSSOM, HTML parser, and layout engine remain explicitly out of scope.

## Required evidence

Permanent Java 8 conformance and bytecode gates prove:

- exact opaque identity forwarding and owner/runtime confinement;
- element metadata, node constants, connection state, and empty detached defaults;
- transformed public bounds versus untransformed offset dimensions;
- a new static `DOMRect` snapshot per bounds call and isolation from caller mutation;
- ECMAScript rounding, including negative zero, NaN, and infinities;
- host contract validation, exception recovery, and reentry rejection;
- one context reference plus one primitive identity in each wrapper;
- one shared context implementing the primitive sink directly;
- exactly one `DOMRect` allocation and no tuple/array/collection for bounds;
- no allocation in offset dimension bytecode; and
- no Android, renderer object, generic collection, concurrency, scheduler, reflection, networking, or
  coordinate-array dependency in production.
