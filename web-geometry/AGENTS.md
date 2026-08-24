# Web geometry rules

- `web-geometry` implements only reached Geometry Interfaces value objects. It is not a renderer, layout engine, CSS parser, or DOM tree.
- Keep `DOMPointReadOnly`/`DOMPoint` and `DOMRectReadOnly`/`DOMRect` backed by inherited primitive `double` fields. Do not replace them with boxed values, maps, arrays, or generic property bags.
- `unrestricted double` values preserve NaN, infinities, and signed zero. Derived rectangle edges and quad bounds use NaN-safe extrema; do not substitute comparisons that suppress NaN or erase signed-zero behavior.
- `DOMQuad` owns four `DOMPoint` objects. Its `p1` through `p4` getters are same-object, while constructor and `fromQuad` inputs are copied by value.
- `DOMQuad.fromRect` creates exactly four owned points and one quad. It must not create temporary coordinate arrays or copy those newly created points again.
- `DOMQuad.getBounds` allocates only the specification-visible result `DOMRect`; calculate directly from the four primitive coordinates.
- Geometry values carry no `RuntimeInstance`. Generated language code owns ordinary object access and thread confinement; this module adds no per-getter runtime checks, locks, atomics, or synchronization.
- Do not implement Java `Serializable` as a substitute for Web structured serialization. `toJSON`, structured clone, `DOMMatrix`, `matrixTransform`, and legacy `DOMRectList` require their own reached/compiler contracts.
- Do not add Android, renderer, scheduler, reflection, URL, networking, or generic collection dependencies.

Run `./scripts/test-geometry.sh` before every change.
