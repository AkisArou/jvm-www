# 0027 — Keep reached DOMMatrix math in primitive fields and allocation-free kernels

Status: accepted for the renderer-facing Geometry Interfaces matrix profile.

## Context

Decision 0026 introduced primitive `DOMPoint`, `DOMRect`, and `DOMQuad` values for renderer-heavy
measurement paths. Reached renderer and intersection code also needs `DOMMatrixReadOnly`,
`DOMMatrix`, and `DOMPoint.matrixTransform()` without introducing Android graphics semantics,
boxed coordinate containers, or a generic matrix library into the Web compatibility layer.

Geometry Interfaces makes several details observable. A six-element matrix sequence is the 2D
`[a, b, c, d, e, f]` form, while a sixteen-element sequence is created as a 3D matrix even when its
numerical values happen to describe an ordinary 2D transform. Mutable writes to 3D components can
clear `is2D`, and that flag is sticky: later writes do not promote the object back to 2D. Point
transformation uses all four homogeneous coordinates. A non-invertible matrix does not throw; its
inverse contains NaN in every component and is not 2D.

These operations occur on hot renderer paths. Representing a matrix as `double[]`, `Double[]`, a
list, a map, an Android `Matrix`, or a third-party algebra object would either allocate on common
operations or import semantics that are not the Geometry Interfaces contract. Mutable transform
methods also must not manufacture one temporary matrix object per translate/scale/rotate/skew.

The selected profile does not yet need CSS transform-string parsing, typed-array projections,
`setMatrixValue`, JSON record lowering, or structured serialization. Those features cross separate
CSS, compiler, or serialization boundaries and must not be approximated here.

## Decision

Extend `web-geometry` with:

```text
DOMMatrixReadOnly
DOMMatrix
DOMPointReadOnly.matrixTransform(DOMMatrixReadOnly)
```

`DOMMatrixReadOnly` contains exactly sixteen inherited primitive matrix components plus one boolean
`is2D` flag. Components use Geometry Interfaces column-major names:

```text
m11 m12 m13 m14
m21 m22 m23 m24
m31 m32 m33 m34
m41 m42 m43 m44
```

The aliases `a`, `b`, `c`, `d`, `e`, and `f` map to `m11`, `m12`, `m21`, `m22`, `m41`, and `m42`.
`DOMMatrix` subclasses the read-only type, mutates the inherited fields directly, and adds no second
matrix representation.

### Construction and `is2D`

The identity constructor is 2D. The six-value constructor and six-element `double[]` sequence build
the standard 2D matrix. A sixteen-value constructor or sixteen-element sequence stores values in
column-major order and sets `is2D` false unconditionally. Copy construction preserves the source
flag. The Java `double[]` overload is a compiler-facing sequence boundary only: values are read into
primitive fields immediately and the caller array is never retained.

The mutable aliases and the six 2D matrix components never clear `is2D`. Writes to
`m13`, `m14`, `m23`, `m24`, `m31`, `m32`, `m34`, or `m43` clear the flag for any value other than
positive or negative zero. Writes to `m33` or `m44` clear it for any value other than one. Once false,
setters never restore it.

Matrix operations carry the same conservative dimensional history. Multiplication remains 2D only
when both operands are 2D. A nonzero Z translation, a Z scale other than one, a nonzero Z scale
origin, or an X/Y rotation axis clears the flag. Algebra that later happens to produce a numerically
2D matrix does not promote it back to 2D.

### Primitive multiplication and transform points

When both operands are 2D, multiplication uses the six affine components directly. No 4x4 loop,
array, or temporary object is needed. The full fallback snapshots both operands into local primitive
variables before writing the target, so `multiplySelf(this)` and `preMultiplySelf(this)` are alias
safe without allocating a copy.

`transformPoint` evaluates the four homogeneous equations directly and allocates one returned
`DOMPoint`. `DOMPointReadOnly.matrixTransform` delegates to that same implementation. A null Java
matrix argument represents the selected compiler lowering of an omitted/empty matrix dictionary and
returns a value copy of the point.

### Allocation-free mutable transforms

`translateSelf`, `scaleSelf`, `rotateSelf`, `rotateFromVectorSelf`, `rotateAxisAngleSelf`,
`skewXSelf`, and `skewYSelf` mutate primitive columns in place. Scaling around an origin is composed
through direct translations and primitive column scaling, not three temporary matrices. Axis-angle
rotation normalizes the axis using `Math.hypot` and post-multiplies its 3x3 rotation block directly.
The ordinary non-mutating methods create their required result `DOMMatrix` once and then use the same
mutable kernels.

The module uses Java `Math.sin`, `cos`, `tan`, `atan2`, and `hypot` only as IEEE-754 primitive math;
no platform transform type defines Web semantics.

### Inversion

A 2D matrix uses the direct affine determinant/inverse formula and remains 2D when invertible. A full
matrix uses a scalar adjugate/determinant kernel with all inputs captured before writes. Neither path
allocates an array or helper object.

A zero or NaN determinant follows Geometry Interfaces non-invertible behavior: all sixteen
components become `NaN` and `is2D` becomes false. `inverse()` performs the same operation on one new
result matrix.

### Profile boundaries

This slice deliberately excludes:

- DOMMatrix CSS transform-string construction and `setMatrixValue`; CSS grammar and unit resolution
  require a separately selected CSS transform parser.
- `toFloat32Array` and `toFloat64Array`; Native TypeScript typed-array ownership and zero-copy ABI
  require a compiler/runtime contract rather than returning an arbitrary Java array as Web storage.
- JSON conversion and structured serialization; Java maps and `Serializable` are not substitutes.
- Android `android.graphics.Matrix`, RenderNode matrices, or renderer-owned transform handles.
- Unreached legacy convenience aliases beyond the selected transform surface.

## Performance consequences

A matrix value is one Java object containing sixteen primitive doubles, one boolean, and its object
header. `DOMMatrix` adds no coordinate storage. Mutable translate, scale, rotate, skew, multiply, and
invert operations allocate no objects or arrays. Two-dimensional multiply and inverse avoid full 4x4
work. Full multiplication is alias safe through local primitives rather than a temporary matrix.
Point transformation allocates only the specification-visible result point.

The only sequence-array path is construction from caller-owned `double[]`; the array is neither
retained nor copied. Generated checked IR may call the primitive six- or sixteen-argument forms and
avoid that boundary entirely.

## Required evidence

Permanent Java 8 conformance and bytecode gates prove:

- identity, six-value, sixteen-value, copy, alias, and invalid sequence-length behavior;
- sixteen-value identity matrices remain `is2D == false` while `isIdentity == true`;
- sticky `is2D` setter and 3D-transform behavior, including negative zero;
- exact homogeneous point transformation and shared `matrixTransform` behavior;
- affine translate/scale/rotation/skew semantics and transform ordering;
- multiply and pre-multiply order plus self-alias safety;
- invertible 2D and full matrices multiply by their inverses to identity;
- singular inversion produces sixteen NaNs and clears `is2D`;
- randomized multiplication, pre-multiplication, and inversion traces exercise the scalar kernels;
- mutable transform bytecode creates no arrays, collections, boxed doubles, helper matrices, or
  operation wrappers;
- `transformPoint` allocates one result point and no coordinate array;
- the matrix classes contain no generated inner adapters; and
- no runtime owner, Android graphics API, generic collection, concurrency primitive, scheduler,
  reflection path, Java serialization, or platform URL/networking dependency enters matrix geometry.
