# ADR 0035: Keep `children` live through primitive relation traversal

- Status: Accepted
- Date: 2026-08-25
- Owners: Web capability and Android renderer integration

## Context

The reached React Native element profile already exposes generation-safe element-only relations:

- `parentElement`
- `firstElementChild`
- `lastElementChild`
- `previousElementSibling`
- `nextElementSibling`
- `childElementCount`

The next reached shape is `Element.children` and its `HTMLCollection` result.

The DOM Standard declares `ParentNode.children` as `[SameObject]`. The returned collection is live:
its length and indexed or named lookup observe the current tree rather than a snapshot captured when
the collection object was created.

A straightforward Java implementation would allocate a child array and an `HTMLCollection` on each
getter call. That is observably wrong for `[SameObject]`, retains stale wrappers, and makes repeated
layout-adjacent reads proportional to both child count and allocation pressure.

The opposite shortcut—making `children` a new view every call while delegating lookups to the
renderer—would avoid child arrays but still violate object identity.

The existing renderer boundary already provides the primitive operations needed to implement a live
collection without extending the Android production table:

- one exact-generation first-child identity;
- one exact-generation next-sibling identity per child;
- one exact-generation parent identity per child;
- one primitive child count;
- direct metadata lookup by exact identity;
- one stable renderer-owned public wrapper per exposed element.

## Decision

### One lazy collection reference per public element wrapper

`ReactNativeElement` gains one nullable `HTMLCollection` field.

The first `getChildren()` call allocates one collection and stores it in that field. Every later call
returns the same object. The field is owner-confined and needs no volatile, lock, or atomic policy.

The public element wrapper therefore remains bounded:

```text
NativeElementContext context
long elementIdentity
HTMLCollection children  // null until first access
```

The optional reference is the minimum representation that preserves `[SameObject]` even after the
element is disconnected. A renderer-slot cache alone is insufficient because unmount increments the
slot generation while caller-held old wrappers and their already-observed collection objects remain
reachable.

### The collection retains no child snapshot

`HTMLCollection` stores only:

```text
NativeElementContext context
long parentIdentity
```

It does not retain:

- a child array;
- a wrapper array;
- a count cache;
- a renderer revision;
- an iterator;
- a map of names;
- an Android object.

Every operation reads the current renderer snapshot.

### Length is a primitive live read

`getLength()` delegates to the existing generation-safe `childElementCount` host scalar.

An unavailable, disconnected, closed, or stale parent returns zero. If the committed first or last
child generation is no longer active, the Android host also returns zero until the renderer publishes
a new complete relation revision.

### Indexed lookup traverses identities, not wrappers

`item(number)` first applies Web IDL unsigned-long conversion to the Java `double` carrier:

- NaN, infinities, and either zero become zero;
- finite values truncate toward zero;
- the result is reduced modulo `2^32`;
- indices above the selected Java array/index range cannot match and return null.

For an in-range index, `NativeElementContext` walks:

```text
parent first-child identity
    -> current child's exact parent identity
    -> current child's next-sibling identity
    -> ...
    -> requested exact identity
```

The context reuses its existing `NativeElementRelationSink`. Intermediate identities never become
public wrappers. Only the requested final identity passes through `createElement`, so a cache miss
allocates exactly one required `ReactNativeElement`.

The walk is O(index) primitive relation reads and O(1) retained memory. This matches the linked
relation representation and avoids an O(n) snapshot allocation for every collection access.

### Named lookup scans current IDs in child order

`namedItem(name)` returns the first direct child whose reached ID equals the requested DOMString.
The scan uses exact identities and calls the host's scalar `getId` directly; it does not expose or
allocate wrappers for nonmatching children.

The selected React Native element profile currently exposes `id`/`nativeID` but no separate HTML
`name` attribute. Therefore its supported collection names are non-empty IDs only. This is narrower
than a full browser HTML document but exact for the reached element model. Empty names return null.
Java null follows DOMString conversion and is searched as the string `"null"`.

Duplicate IDs resolve to the first child in current relation order.

### Relation validation remains generation-safe

Each traversed child must:

- still resolve under the exact committed generation;
- publish the queried parent as its current `parentElement`;
- provide the next relation needed for the bounded walk.

A stale or inconsistent step makes the lookup unavailable rather than exposing a replacement slot.
The walk is bounded by the validated child count and rejects an immediate cycle back to the first
child. The renderer remains responsible for publishing a coherent complete tree revision.

### Lifecycle behavior

A caller-held collection remains the same object after its parent disconnects. Its operations then
observe zero length and null items because the retained parent identity is stale.

If the renderer reuses the same table slot, the replacement has a different generation and receives
a different public wrapper and, if requested, a different collection.

Runtime ownership checks still occur before every collection operation. Foreign-thread, idle, and
closed-runtime access fails before touching the renderer host.

Closing the Android host requires no new production cleanup because the collection retains only the
core context and opaque identity. Existing generation, relation, metadata, and wrapper arrays already
become unavailable and are released by the host.

## Performance contract

The common paths are:

```text
children getter after first access:
    owner check + one field load

length:
    owner check + primitive count/endpoint validation

item(index), cached target wrapper:
    O(index) primitive relation reads + one wrapper-table load

namedItem(name), cached target wrapper:
    O(child count) primitive relation/ID reads + one wrapper-table load
```

Allowed allocations are:

- one `HTMLCollection` on the first `children` access for a public element;
- one `ReactNativeElement` when the requested returned child has never before been exposed.

The selected paths must not allocate:

- child snapshots;
- coordinate or identity arrays;
- iterators;
- tuple/result objects;
- boxed indices or identities;
- maps or collection nodes;
- callback adapters;
- tasks, Futures, coroutines, or Android messages.

## Alternatives rejected

### Return a fresh collection on every getter

Rejected because it violates `[SameObject]` and creates avoidable allocation.

### Store the collection only in the Android slot table

Rejected because an old caller-held wrapper must continue returning its previously observed
collection after unmount and slot-generation reuse.

### Snapshot child wrappers into an array

Rejected because the result would not be live, would retain stale children, and would allocate O(n)
for ordinary reads.

### Traverse through public wrappers

Rejected because `item(n)` or a failed named lookup could allocate wrappers for every intermediate
child. Primitive identity traversal exposes only the result requested by application code.

### Add a second child-index table to Android

Rejected for this slice. The existing first-child/next-sibling representation is sufficient and
keeps publication atomic with the already-tested relation snapshot. A future renderer may add a
revisioned index only with evidence that random indexed access dominates and the additional retained
memory is worthwhile.

### Implement node-level `childNodes` at the same time

Rejected. `childNodes` must represent text and other node kinds and has different `NodeList`
semantics. Treating the element-only relation table as a node tree would be observably incorrect.

## Verification

Permanent Java 8 conformance proves:

- `children` is the same object across repeated reads, tree updates, and disconnection;
- collection length and ordering are live;
- indexed lookup performs Web IDL unsigned-long conversion;
- indexed traversal exposes only the requested final wrapper;
- named lookup uses current IDs, duplicate-first ordering, empty-name handling, and null DOMString
  conversion;
- stale generations and slot reuse cannot alias replacement elements;
- old and replacement parent generations receive distinct collection objects;
- foreign-thread and closed-runtime access is rejected before host access;
- production bytecode contains one lazy collection allocation and no snapshot arrays, iterators,
  maps, boxing, schedulers, or platform dependencies.
