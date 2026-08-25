# ADR 0034: Publish element relations as generation-safe primitive identities

## Status

Accepted.

## Context

The reached React Native element profile already exposes stable renderer-owned public wrappers,
committed geometry, scalar client/scroll metrics, and `offsetParent`. The next useful read-only
surface is the element-only part of DOM traversal:

- `parentElement`
- `firstElementChild`
- `lastElementChild`
- `previousElementSibling`
- `nextElementSibling`
- `childElementCount`

These values are live reads of the renderer's current committed revision. They must return the same
public object that another route to that mounted element returns. A stale renderer identity must
never start referring to a replacement element after slot reuse.

React Native's JavaScript implementation obtains child arrays and filters them for element
relations. Repeating that representation in the JVM profile would allocate arrays or collection
wrappers on hot reads and would duplicate renderer state in the Web layer. Returning only slot
numbers would be faster but incorrect because a released slot can be reused.

The core also does not yet expose text nodes, document nodes, `NodeList`, or `HTMLCollection`.
Aliasing node-level APIs to the element-only profile would silently approximate observable types and
ordering.

## Decision

### Keep public element wrappers unchanged

`ReactNativeElement` remains exactly:

```text
NativeElementContext context
long elementIdentity
```

No parent, child, sibling, count, runtime, array, or cache field is added per wrapper.

The renderer-owned `NativeElementPublicInstanceStore` remains the identity authority. A related
identity is resolved through `NativeElementContext.createElement(identity)` after the synchronous
host read has finished:

```text
relation cache hit
  -> return the exact stored wrapper

relation cache miss
  -> allocate one ReactNativeElement
  -> register that exact wrapper with the renderer
  -> return it
```

Therefore repeated traversal to the same mounted identity is allocation-free and object-stable.
First exposure allocates only the specification-visible wrapper that must exist for the returned Web
value.

### Reuse one primitive relation sink

Add:

```java
public interface NativeElementRelationSink {
    void setRelatedElement(long elementIdentity);
}
```

`NativeElementContext` directly implements this interface alongside the existing rectangle and
offset sinks. It adds one primitive related identity and one primitive active element identity to
its shared read state.

Each related-element host method follows the existing synchronous publication contract:

```java
boolean readParentElement(long identity, NativeElementRelationSink sink);
boolean readFirstElementChild(long identity, NativeElementRelationSink sink);
boolean readLastElementChild(long identity, NativeElementRelationSink sink);
boolean readPreviousElementSibling(long identity, NativeElementRelationSink sink);
boolean readNextElementSibling(long identity, NativeElementRelationSink sink);
```

A return value of `true` requires exactly one sink write. A return value of `false` requires no
write. The sink cannot be retained or used outside that call.

The shared read-kind guard now distinguishes rectangle, offset, and related-element reads. It rejects
same-kind and cross-kind reentry, duplicate writes, missing writes, false-after-write behavior,
retained-sink use, and a host that reports the current element as its own relative. Host exceptions
escape unchanged and the guard is restored in a `finally` block.

The core relation sink intentionally does not reserve any `long` value as null. Absence is carried by
the host method's boolean result, so an alternate renderer may use the complete identity domain.

### Keep child count scalar

`childElementCount` is exposed through:

```java
int getChildElementCount(long identity);
```

The selected Android profile supports at most 1,048,575 simultaneous element slots, so every
possible count fits in a nonnegative Java `int`. `NativeElementContext` rejects a negative host
result explicitly. The ordinary read allocates nothing.

### Publish one Android relation snapshot per element

`AndroidNativeElementHost` adds:

```text
long[] elementRelations
int[] childElementCounts
```

The relation array has a fixed stride of five:

```text
0 parent element
1 first element child
2 last element child
3 previous element sibling
4 next element sibling
```

A new state bit marks a committed relation snapshot. The renderer publishes all five identities and
the count through one owner-Looper call:

```java
commitElementRelations(
    elementIdentity,
    parentElementIdentity,
    firstElementChildIdentity,
    lastElementChildIdentity,
    previousElementSiblingIdentity,
    nextElementSiblingIdentity,
    childElementCount);
```

Android element identities are positive, so `0L` is an adapter-private absence or inaccessible-root
sentinel. Every nonzero relation must resolve to a currently mounted exact generation and must not be
the element itself.

Publication validates these endpoint invariants before changing state:

- A negative count is rejected.
- A zero count requires absent first and last children.
- A count of one requires identical nonzero first and last child identities.
- A larger count requires distinct nonzero first and last child identities.
- The same nonzero identity cannot be both previous and next sibling.
- The count cannot exceed the number of other active table elements.

All relation values and the count are written before the availability bit is published. Renderer
publication and Web reads occur on the same Looper, so language code observes the old complete
snapshot or the new complete snapshot, never a partially written row.

### Validate generations again on read

A relation read resolves the owning slot, checks the availability bit, loads one selected identity,
and validates that identity's complete generation before calling the reusable sink.

If the related element was unmounted, the read returns unavailable. Reusing its slot under a new
generation does not satisfy the old relation. The renderer must recommit the affected relationship
against the replacement identity.

`childElementCount` revalidates the committed first and last child generations before returning a
nonzero count. This prevents a stale endpoint snapshot from exposing a count after either endpoint
was released. The renderer still owns publication of complete tree revisions, including changes to
non-endpoint children.

### Keep element-only and node-level traversal separate

This decision does not add:

- `parentNode`
- `firstChild` or `lastChild`
- `previousSibling` or `nextSibling`
- `childNodes`
- `children`
- `NodeList` or `HTMLCollection`
- text or document wrappers

Those APIs have observable node kinds, collection identity, ordering, and allocation contracts that
must be designed independently. The element-only getters are not used as silent substitutes.

## Performance consequences

A cached related-element read performs:

```text
owner/runtime check
-> one direct host relation call
-> one owner-slot lookup
-> one relation long load
-> one related-generation lookup
-> one sink write
-> one public-wrapper reference-array lookup
```

It allocates nothing.

A first relation exposure performs the same work and then allocates exactly one
`ReactNativeElement`, which is retained in the existing public-instance table.

`childElementCount` performs primitive state, count, and endpoint loads only.

Renderer publication performs five `long[]` stores, one `int[]` store, and one state-byte update.
Capacity growth remains proportional to maximum simultaneous mounted elements and uses
`System.arraycopy`. No per-relation object, tuple, map entry, boxed identity, callback adapter,
`View`, lock, task, Future, coroutine, or Handler message is introduced.

## Lifecycle consequences

Unmount clears the element's outgoing relation row and count, public-wrapper reference, metadata,
and offset parent before returning its slot to the generation-incrementing free list.

Other rows may temporarily retain the old full identity until the renderer publishes the next
revision. Such reads return unavailable and cannot alias the replacement occupant.

Closing the Android host releases the relation and count arrays with the other committed tables.
Reads after close return null or zero through the normal detached defaults. Renderer mutations after
close fail explicitly.

## Rejected alternatives

### Return a relation tuple or array

Rejected because every getter would allocate or retain a multi-value carrier and would encourage
copying renderer state into the core.

### Use a generic map from identity to wrapper or relations

Rejected because it boxes identities, adds entries per element, duplicates the renderer's lifecycle,
and makes stale-generation behavior harder to prove.

### Store relation fields on every `ReactNativeElement`

Rejected because wrappers would become live mutable renderer mirrors, every host component would pay
the memory cost, and commit ordering would leak into Web objects.

### Use slot indexes without generations

Rejected because an old wrapper or relation could silently resolve to a new element after slot
reuse.

### Recompute element siblings from a child array on every read

Rejected because it requires an array/collection boundary, linear scans, and repeated wrapper
materialization in a hot navigation path.

### Alias node-level traversal to element traversal

Rejected because React Native trees can contain non-element nodes and document/root behavior is
observable. Unsupported node shapes must remain explicit rather than approximately typed.

## Verification

`./scripts/test-native-element-relations.sh` permanently verifies:

- Stable renderer-owned wrapper identity on cache hits.
- Exactly one wrapper registration on first relation exposure.
- Parent, first/last child, previous/next sibling, and count semantics.
- Absent and inaccessible-root defaults.
- A core host identity of zero without a null-sentinel collision.
- Missing writes, false-after-write, duplicate writes, retained sinks, self relations, and reentry.
- Owner-turn, foreign-thread, and closed-runtime refusal before host access.
- Android snapshot validation and independent availability from geometry, offsets, and metrics.
- Stale-generation failure before and after slot reuse, followed by explicit recommit recovery.
- Table growth, foreign-Looper refusal, close-time release, and mutation refusal after close.
- Bytecode evidence for one reusable core sink, strided primitive Android storage, direct primitive
  loads/stores, and the absence of maps, tuples, wrapper adapters, boxing, scheduling, and locking.
