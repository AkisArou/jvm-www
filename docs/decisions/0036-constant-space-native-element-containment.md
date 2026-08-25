# ADR 0036: Walk native-element containment through exact-generation parents

- Status: Accepted
- Date: 2026-08-25
- Owners: Web capability and Android renderer integration

## Context

The reached React Native element profile now exposes stable public wrappers, exact-generation
parent/child/sibling relations, and a live `children` collection. The next useful `Node` operation is
`contains(other)`.

The [DOM Standard](https://dom.spec.whatwg.org/#dom-node-contains) defines `contains(other)` as an
inclusive-descendant test. A node therefore contains itself, including while disconnected. It
[defines `isSameNode(other)`](https://dom.spec.whatwg.org/#dom-node-issamenode) as the legacy method
form of reference identity.

The selected JVM profile currently has a public carrier for renderer-owned elements, not for text,
comment, document, attribute, or document-fragment nodes. This slice must be exact for the supported
`ReactNativeElement` and null inputs without pretending that unsupported node kinds exist.

A naive implementation could repeatedly call `getParentElement()`. That would expose or allocate
public wrappers for every ancestor first encountered during a containment check. Another common
implementation would build a `HashSet` of visited ancestors to handle malformed cycles. Both choices
put allocation into a query that can be a frequent renderer hot path.

The existing renderer relation ABI already provides the required primitive operation:

```text
child exact-generation identity
    → readParentElement(child, reusable relation sink)
    → parent exact-generation identity
```

## Decision

`ReactNativeElement` exposes:

```java
boolean isSameNode(ReactNativeElement other);
boolean contains(ReactNativeElement other);
```

`isSameNode` performs the owner/runtime check and returns Java reference identity. Stable renderer
public-wrapper identity makes that the exact selected representation of the DOM operation.

`contains` passes the two wrappers' contexts and primitive identities to `NativeElementContext`.
The context:

1. validates owner-language execution;
2. returns false for null or a different renderer context;
3. returns true immediately when both exact identities are equal;
4. follows only `readParentElement` primitive identities;
5. returns true when the queried inclusive ancestor is reached;
6. returns false when the relation becomes unavailable or a cycle is detected.

The core does not ask `isConnected()` first. Connectivity is not the definition of containment, and
self-containment remains true for a detached wrapper.

## Constant-space cycle detection

A renderer tree should be acyclic, but a transport-independent host is still an adversarial
boundary. It can publish a longer parent cycle even though the sink rejects an element returned as
its own direct parent.

The walk uses Brent cycle detection with primitive local variables:

```text
current identity
cycle anchor identity
power
distance from anchor
```

It performs one parent-relation read for each advanced node and retains no ancestor collection. On a
cycle that does not include the queried ancestor, the walk terminates in O(tail + cycle) reads and
O(1) memory.

The algorithm deliberately keeps cycle state in method locals. `NativeElementContext` and
`ReactNativeElement` gain no ancestry field, array, map, iterator, or reusable per-query buffer.

## Context and null behavior

A null argument returns false after the receiver's normal owner/runtime check.

An element from another `NativeElementContext` returns false without reading either renderer. This is
the selected representation of nodes from different trees or runtime/renderer pairs.

The generic core does not reserve identity zero. Context identity is checked separately, so an exact
core identity of zero can still contain descendants.

## Android behavior

No Android production code or table shape changes in this slice.

`AndroidNativeElementHost.readParentElement` already:

- checks the exact owner Looper;
- resolves the child generation;
- resolves the committed parent generation again at read time;
- writes the parent identity through the reusable primitive relation sink;
- returns false for stale, unmounted, unavailable, or closed-host relations.

Consequently, releasing a parent and reusing its slot cannot repair an old ancestry chain. The
renderer must commit the replacement generation explicitly before containment observes it.

A malformed multi-element cycle can be represented only by inconsistent renderer publication. The
core cycle detector bounds that failure without asking the Android host to allocate or perform a
transitive validation pass on every relation commit.

## Performance contract

The common successful check is:

```text
one receiver owner/runtime check
+ one exact parent relation read per ancestor level
+ primitive comparisons
```

Self and cross-context checks perform no renderer read.

The production containment path allocates no:

- public ancestor wrapper;
- identity array;
- iterator;
- `HashSet` or other collection;
- boxed identity or counter;
- callback, task, Promise, Future, coroutine, or `Runnable`;
- Android object.

Time is O(h), where h is the traversed parent depth. Auxiliary memory is O(1).

## Failure and lifecycle behavior

A stale or missing relation ends the walk with false. The operation does not infer ancestry from
slot numbers, wrapper retention, child counts, or offset parents.

A receiver used outside its runtime owner turn, from a foreign thread, or after runtime closure fails
before renderer access, like the rest of the native-element surface.

A detached wrapper still returns true for `contains(self)` and `isSameNode(self)`. If its runtime is
closed, the owner/runtime failure takes precedence because generated language execution is no longer
allowed.

## Rejected alternatives

### Walk public parent wrappers

Rejected because it can allocate and publish every intermediate ancestor merely to answer a boolean
query.

### Allocate a visited set

Rejected because ordinary containment would allocate and box renderer identities. Constant-space
cycle detection provides the required bounded failure behavior.

### Add an Android ancestor cache or depth table

Rejected because relations change by renderer revision, cache invalidation would duplicate tree
state, and stale generations would need another ownership protocol.

### Use `offsetParent`

Rejected because offset ancestry is a layout concept and is not DOM tree ancestry.

### Treat disconnection as always false

Rejected because containment is inclusive and a node contains itself even when disconnected.

### Implement unsupported node kinds as elements

Rejected because text, document, attribute, and fragment carriers have distinct semantics and need
separate reached contracts.

## Evidence

`NativeElementContainmentConformance` covers inclusive self, direct and deep descendants, siblings,
detached elements, null, cross-context values, exact reference identity, zero-valued core identities,
bounded cyclic graphs, and owner/runtime confinement.

`AndroidNativeElementContainmentConformance` covers committed hierarchy traversal, stale parent
generation failure, slot reuse without aliasing, explicit recommit recovery, detached self,
closed-host behavior, and a malformed two-node cycle.

`scripts/test-native-element-containment.sh` compiles all involved production and testkit sources
against the Java 8 API surface, runs both suites under a timeout, and proves from bytecode that:

- no per-element field was added;
- containment calls the primitive relation helper;
- containment does not call `createElement` or `getParentElement`;
- identity and containment methods allocate no object, array, collection, iterator, or boxed value;
- no ancestry adapter or generated inner class exists.
