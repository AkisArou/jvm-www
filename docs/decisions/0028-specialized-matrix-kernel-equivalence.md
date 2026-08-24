# 0028 — Falsify specialized matrix kernels against independent full multiplication

Status: accepted for optimized Geometry Interfaces matrix operations.

## Context

Decision 0027 keeps `DOMMatrix` transforms in primitive scalar fields and specializes common
operations so renderer paths do not allocate temporary arrays or matrix objects. That representation
is valuable, but hand-expanded matrix expressions are vulnerable to coefficient drift: an expression
can compile, preserve identity cases, and still read the wrong source row on a non-identity matrix.

The initial axis-angle tests began from identity or affine matrices. The generic multiplication and
inversion traces exercised different production methods, so they could not falsify every coefficient
inside the private 3x3 post-multiply kernel. A third-column, second-row expression consequently read
`m23` where column-major multiplication requires `m22`.

Optimized production code must remain scalar and allocation-free, but performance is subordinate to
exact Geometry Interfaces multiplication and transform ordering.

## Decision

Correct the 3x3 post-multiply expression for the new `m32` component to:

```text
old m12 * r31 + old m22 * r32 + old m32 * r33
```

The permanent matrix conformance suite now treats every specialized rotation kernel as an
optimization of ordinary column-major multiplication rather than as its own source of truth.

Test-only code builds axis-angle matrices with an independent Rodrigues formula and multiplies them
using a generic three-loop 4x4 reference implementation. It compares that result with:

- `rotateAxisAngleSelf` on an asymmetric, non-identity full matrix;
- `rotateSelf(x, y, z)` in its required Z-then-Y-then-X order;
- Z-axis rotation of a non-identity affine matrix;
- a zero-axis no-op; and
- 10,000 deterministic randomized full-matrix axis-angle and ordered-rotation cases.

The reference path intentionally does not call `rotateAxisAngleSelf`, `postMultiply3x3`, or another
production transform helper. Test arrays and loops are acceptable evidence; they do not enter the
production artifact.

## Performance consequences

The production change replaces one scalar operand and adds no field, branch, loop, allocation, or
method call. The optimized 3x3 kernel remains an in-place operation over captured primitive locals.
The additional cost exists only in the Java 8 conformance gate.

Future specialized matrix kernels may use direct formulas, SIMD-oriented lowering, or narrower 2D
paths only when permanent evidence compares them with an independent reference on asymmetric
non-identity inputs. Identity-only examples are insufficient because many coefficient-placement
errors disappear when off-diagonal values are zero.

## Required evidence

Permanent tests and structural gates prove:

- the previous incorrect coefficient fails the deterministic non-identity case at matrix component
  index 9;
- mixed-axis rotation equals independent full multiplication for every component;
- three-angle rotation preserves the required Z-Y-X post-multiplication order;
- Z-axis affine rotation remains 2D while a mixed axis remains 3D;
- a zero-length axis leaves values and dimensional history unchanged;
- 10,000 randomized axis-angle operations match the independent reference;
- 10,000 randomized ordered rotations match three independent reference products; and
- production matrix mutation still creates no array, collection, boxed number, helper matrix, or
  operation wrapper.
