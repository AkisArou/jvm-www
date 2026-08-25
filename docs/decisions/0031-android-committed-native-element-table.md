# 0031 — Keep Android native-element state in a Looper-confined committed snapshot table

Status: accepted for the first Android renderer adapter.

## Context

Decision 0030 introduced a renderer-independent `ReactNativeElement` value and a synchronous
`NativeElementHost` boundary. The Web object retains only an opaque generation-safe identity and a
shared owner-confined context. Its transformed `getBoundingClientRect()` and untransformed offset
reads require a concrete renderer host, but that host must not leak Android or renderer objects into
the Web layer.

A direct `android.view.View` registry would be the wrong semantic boundary. A modern renderer may
flatten host components, serve layout from a committed shadow-tree revision, or have an element with
valid layout semantics that is not represented by a stable Java `View`. Android view geometry also
mixes layout, transforms, clipping, parent attachment, and window coordinates in APIs whose choices
are not automatically React Native's DOM-shaped contract. Retaining a `View` in every public wrapper
would additionally couple wrapper lifetime to platform lifecycle.

The renderer already knows when a commit publishes new metadata and layout. The cheapest exact
boundary is therefore a Looper-confined table of the renderer's committed primitive snapshots. Web
reads resolve the opaque identity against that table synchronously. The first Android slice does not
need a `Handler`, cross-thread publication, a platform rectangle, or a per-element Java node.

## Decision

Add:

```text
web-native-elements-android
web-native-elements-android-testkit
```

The production module exposes one concrete class:

```text
AndroidNativeElementHost
```

It implements `NativeElementHost` and `AutoCloseable`.

### Exact Looper ownership

`AndroidNativeElementHost.forCurrentLooper()` captures `Looper.myLooper()` and refuses construction
on a thread without a Looper. Every renderer mutation and every Web-facing host read compares the
current Looper by identity with that captured object.

The application must create the `RuntimeInstance`, `NativeElementContext`, renderer table, and public
wrappers on the same owner Looper. The adapter never posts work to that Looper. A foreign-thread call
fails before reading or mutating committed state.

### Opaque generation-safe identities

The table allocates positive `long` identities with this internal layout:

```text
low 20 bits: one-based slot
remaining positive bits: slot generation
```

The Web layer still treats the value as opaque. Unmounting invalidates the exact identity. A reusable
slot increments its generation before it can be mounted again, so a stale `ReactNativeElement` can
never begin addressing a newer renderer element. A slot is retired if its generation space is ever
exhausted.

Up to 1,048,575 elements may be simultaneously represented by one host. The identity is an internal
Java capability and is not exposed as a JavaScript number.

### Compact committed-state table

The host stores no per-element object. One lazily allocated table contains:

```text
long[] generations
int[] nextFreeSlot
byte[] states
String[] metadata       // [tagName, id] per slot
double[] layout         // 8 doubles per slot
```

The layout stride is:

```text
transformed x, y, width, height
untransformed x, y, width, height
```

An intrusive primitive free list provides O(1) ordinary slot allocation and release. Capacity doubles
only when simultaneous mounted demand exceeds the existing arrays. Growth uses `System.arraycopy`.
Strings are immutable renderer values and are retained directly; null tag names and IDs normalize to
the empty string.

### Renderer publication

The renderer-facing operations are:

```text
mountElement
commitMetadata
commitLayout
clearCommittedLayout
unmountElement
```

`commitLayout` receives both transformed and untransformed rectangles in one call. It writes all eight
primitive coordinates before publishing the layout-available state. Because publication and reads are
confined to one Looper and the method calls no external code, a Web read cannot observe a partially
updated element snapshot.

Coordinates remain unrestricted doubles. The host does not clamp, normalize, round, or reinterpret
NaN, infinities, signed zero, or negative dimensions. Offset rounding remains the responsibility of
the renderer-independent `NativeElementContext`.

A mounted element may temporarily have no committed layout. In that state it remains connected but
measurement returns unavailable without touching the caller's rectangle sink.

### Direct Web reads

`isConnected`, `getTagName`, and `getId` decode and validate the primitive identity directly.
`measureBoundingClientRect` selects the transformed or untransformed four-value range and writes those
four doubles directly to the reusable `NativeElementRectSink` supplied by `NativeElementContext`.

The measurement path allocates no rectangle, tuple, array, callback, wrapper, boxed coordinate, map
entry, or task. The only specification-visible allocation remains `getBoundingClientRect()`'s returned
`DOMRect` in the renderer-independent layer. Offset reads remain allocation-free.

A stale, unknown, unmounted, layout-unavailable, or closed identity returns the detached/unavailable
host result. No stale layout or metadata is exposed.

### Lifecycle

`close()` is owner-confined and idempotent. It marks the table closed, releases every backing array
and metadata reference, clears counts, and makes every previously issued identity permanently
unavailable. Web reads then observe the same detached defaults as another unavailable element.
Renderer mutations after close fail explicitly.

The renderer/application must close the runtime before releasing its UI owner and close this table on
the same Looper. The table is not a `RuntimeOwnedResource`: it is renderer infrastructure rather than
an asynchronous operation, and runtime shutdown must not guess the renderer's lifecycle order.

## Performance consequences

A host allocates nothing until the first mount. Thereafter, ordinary mount, metadata/layout commit,
unmount, connection lookup, metadata lookup, and measurement are primitive array operations. Slot
allocation and release are O(1); table growth is O(current simultaneous capacity) and occurs
geometrically.

There is no per-element host node, identity box, `SparseArray`, `LongSparseArray`, `Map`, lock, atomic,
weak reference, `Handler`, `Runnable`, Future, coroutine, platform rectangle, matrix, or Android
`View`. Measurement performs one identity decode, one state check, four primitive loads, and one sink
call.

## Profile limits

- A concrete renderer integration must publish its own current committed layout and normalized
  metadata into this table. This module does not inspect React Native private shadow-node classes.
- The adapter deliberately does not derive DOM-shaped geometry from `android.view.View` APIs.
- Tree traversal, parent/public-instance lookup, text content, pointer capture, focus, scrolling,
  direct manipulation, and asynchronous legacy measurement remain separate slices.
- Cross-thread renderer commits would require an explicit immutable publication protocol. They are
  not silently synchronized or posted by this owner-confined adapter.
- A general browser DOM, CSSOM, layout engine, and Android view hierarchy wrapper remain out of scope.

## Required evidence

Permanent Java 8 conformance and bytecode gates prove:

- exact current-Looper attachment and foreign-thread refusal;
- positive distinct identities, generation-safe slot reuse, and stale-identity rejection;
- compact primitive/reference-array storage with no per-element host object;
- metadata normalization, connection state, active counts, and growth beyond initial capacity;
- transformed versus untransformed committed rectangle selection;
- unrestricted-double preservation and layout-unavailable behavior;
- atomic eight-value layout publication from the observable single-thread model;
- direct reusable-sink measurement with no allocation;
- end-to-end `NativeElementContext` and `ReactNativeElement` behavior, including static snapshots and
  ECMAScript offset rounding;
- owner-confined close, identity invalidation, metadata release, and mutation refusal; and
- no `View`, Android graphics object, sparse/generic map, collection, lock, atomic, scheduler,
  reflection path, networking dependency, or generated inner adapter in production.
