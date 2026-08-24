# 0026 — Represent reached Web geometry values with primitive fields

Status: accepted for the first renderer-facing Geometry Interfaces profile.

## Context

The Web Mobile profile now has runtime scheduling, networking, URL, buffered bodies, and timing.
Renderer interoperability and APIs such as layout measurement and intersection observation also need
standard geometry values. React Native's DOM-shaped native-element APIs use `DOMRectReadOnly` for
layout and intersection records, while the Geometry Interfaces specification defines the shared point,
rectangle, and quadrilateral semantics.

These objects are synchronous language values. Giving each value a `RuntimeInstance`, property map,
boxed coordinate array, or host wrapper would add overhead to layout-heavy paths without protecting
any asynchronous state. Conversely, replacing their algorithms with convenient Java value semantics
would be observably wrong: Geometry coordinates are `unrestricted double`, rectangles may have
negative dimensions, derived edges prefer NaN, quadrilateral points are live same-object values, and
constructors copy dictionary inputs.

The first reached slice does not yet need transformation matrices, CSS transform parsing, legacy
`DOMRectList`, JSON record lowering, or structured serialization. Those shapes require separate
compiler or renderer contracts rather than approximate Java substitutes.

## Decision

Add two Java 8 modules:

```text
web-geometry
web-geometry-testkit
```

The production module exposes:

```text
DOMPointReadOnly
DOMPoint
DOMRectReadOnly
DOMRect
DOMQuad
```

### Primitive point and rectangle storage

`DOMPointReadOnly` contains exactly four primitive fields:

```text
double x
double y
double z
double w
```

`DOMPoint` inherits those fields, adds setters, and introduces no coordinate storage of its own.
Constructors provide the Web defaults
`x = 0`, `y = 0`, `z = 0`, and `w = 1`. `fromPoint` creates a new object and copies the four values.
A null Java dictionary argument represents WebIDL's empty dictionary; compiler-generated dictionary
lowering may call the primitive four-argument form directly.

`DOMRectReadOnly` similarly contains:

```text
double x
double y
double width
double height
```

`DOMRect` adds setters without duplicating storage. Width and height remain unrestricted and may be
negative, infinite, signed zero, or NaN.

The derived edges are calculated on every read:

```text
top    = NaN-safe minimum(y, y + height)
right  = NaN-safe maximum(x, x + width)
bottom = NaN-safe maximum(y, y + height)
left   = NaN-safe minimum(x, x + width)
```

The implementation uses primitive `Math.min` and `Math.max`, which propagate NaN and preserve the
relevant signed-zero extrema. It does not normalize dimensions or cache derived edges, so a mutable
`DOMRect` is immediately reflected by later edge reads.

### Same-object quadrilateral points

`DOMQuad` owns four final `DOMPoint` references. A public constructor converts each point dictionary
into a new mutable `DOMPoint`, including default points for omitted/null dictionaries. Mutating the
source point after construction therefore does not affect the quad.

The `p1`, `p2`, `p3`, and `p4` getters return those same owned point objects on every call. Mutating a
returned point changes the quadrilateral, as required by the Geometry Interfaces same-object
attributes.

`fromRect` creates exactly four points at:

```text
(x,         y)
(x + width, y)
(x + width, y + height)
(x,         y + height)
```

A private owned-point constructor prevents those freshly created points from being copied a second
time. `fromQuad` does copy all four source points, producing an independent quadrilateral.

`getBounds` reads the eight current x/y coordinates directly, computes NaN-safe minima and maxima,
and allocates one result `DOMRect`. It creates no coordinate array, iterator, collection, boxed
number, or intermediate rectangle.

### Ordinary value ownership

These classes intentionally contain no `RuntimeInstance`. They are ordinary synchronous objects in
the generated language heap, not capability handles or asynchronous state machines. Generated code
and renderer bindings remain responsible for owner-thread access to mutable values. Adding a runtime
reference and a branch to every getter/setter would penalize common measurement paths without
providing cross-thread publication semantics.

The classes do not implement Java `Serializable`, value-based `equals`, or Java record semantics.
Java serialization is not Web structured serialization, and Java value equality would violate normal
JavaScript object identity.

## Performance consequences

A point or rectangle allocates one Java object containing only four primitive doubles plus its object
header. Mutable subclasses add no coordinate fields. Reads and writes are direct field operations.
Derived rectangle edges are constant-time primitive arithmetic.

A quad allocates itself and its four specification-visible mutable points. `fromRect` performs those
five allocations exactly once. `getBounds` performs primitive math and allocates only its required
result rectangle. No generic container, dictionary wrapper, runtime owner, lock, atomic, callback,
Future, coroutine, executor, Android object, reflection path, or scheduler enters the module.

## Profile limits

- `DOMPoint.matrixTransform`, `DOMMatrixReadOnly`, and `DOMMatrix` are a following geometry slice.
  Matrix string parsing must not be approximated with an unrelated Java or Android transform parser.
- `toJSON` needs the compiler's plain-object/record ABI; returning a Java map or another geometry
  object would not be specification-correct.
- Structured serialization needs the selected structured-clone boundary. Java `Serializable` is
  explicitly excluded.
- `DOMRectList` is a legacy Window-only compatibility interface and is not selected for the mobile
  renderer profile.
- Renderer-owned element/document handles and layout measurement are separate capabilities. This
  module supplies only the standard value objects they return.

## Required evidence

Permanent Java 8 conformance and bytecode gates prove:

- point and rectangle constructor defaults and copy identity;
- unrestricted-double preservation, including NaN, infinities, and signed zero;
- exact positive and negative rectangle edge calculations;
- mutable point/rectangle setters update inherited primitive storage;
- rectangle edges prefer NaN rather than silently choosing the finite operand;
- quad construction copies source point dictionaries but exposes stable same-object owned points;
- `fromRect` preserves negative dimensions and creates exactly four owned points without recopying;
- quad bounds reflect live point mutations and propagate NaN independently by axis;
- point and rectangle classes contain primitive fields rather than boxed/container storage;
- `DOMQuad.getBounds` allocates no array, collection, or boxed coordinate and creates one result
  rectangle; and
- no runtime, Android, collection, scheduler, Java serialization, reflection, or platform parser
  dependency enters production geometry.
