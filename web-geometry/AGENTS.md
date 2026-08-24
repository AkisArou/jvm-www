# Web geometry rules

- `web-geometry` implements only reached Geometry Interfaces value objects. It is not a renderer, layout engine, CSS parser, or DOM tree.
- Keep `DOMPointReadOnly`/`DOMPoint` and `DOMRectReadOnly`/`DOMRect` backed by inherited primitive `double` fields. Do not replace them with boxed values, maps, arrays, or generic property bags.
- `unrestricted double` values preserve NaN, infinities, and signed zero. Derived rectangle edges and quad bounds use NaN-safe extrema; do not substitute comparisons that suppress NaN or erase signed-zero behavior.
- `DOMQuad` owns four `DOMPoint` objects. Its `p1` through `p4` getters are same-object, while constructor and `fromQuad` inputs are copied by value.
- `DOMQuad.fromRect` creates exactly four owned points and one quad. It must not create temporary coordinate arrays or copy those newly created points again.
- `DOMQuad.getBounds` allocates only the specification-visible result `DOMRect`; calculate directly from the four primitive coordinates.
- `DOMMatrixReadOnly` owns sixteen primitive `double` matrix components plus the observable `is2D` flag. `DOMMatrix` inherits that storage and adds no coordinate container or duplicate matrix state.
- Six-value matrix sequences are the 2D `[a,b,c,d,e,f]` form. Sixteen-value sequences are observably 3D even when their numerical components equal a 2D matrix. Mutable 3D-component writes may clear `is2D`, and once false it never becomes true through setters or transforms.
- Mutable matrix transforms use direct primitive kernels. Keep the 2D multiply/invert fast paths and do not allocate temporary matrices, arrays, lists, boxed numbers, or transform-operation objects in `multiplySelf`, `preMultiplySelf`, translation, scaling, rotation, skew, or inversion.
- A non-invertible matrix becomes sixteen NaN components with `is2D == false`. Do not throw or return the original matrix.
- `DOMPoint.matrixTransform` and `DOMMatrix.transformPoint` share the same 4x4 primitive formula and allocate only the specification-visible result point.
- Geometry values carry no `RuntimeInstance`. Generated language code owns ordinary object access and thread confinement; this module adds no per-getter runtime checks, locks, atomics, or synchronization.
- Do not implement Java `Serializable` as a substitute for Web structured serialization. Matrix CSS-string parsing, typed-array projection, `toJSON`, structured clone, and legacy `DOMRectList` require their own reached/compiler contracts.
- Do not add Android graphics matrices, renderer, scheduler, reflection, URL, networking, or generic collection dependencies.

Run both `./scripts/test-geometry.sh` and `./scripts/test-geometry-matrix.sh` before every matrix-related change.
