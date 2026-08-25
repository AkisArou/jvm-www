# Native element rules

- `web-native-elements` implements only the reached read-only React Native element value surface. It is not a browser DOM tree, renderer, layout engine, Android view registry, or mutation API.
- Keep each `ReactNativeElement` wrapper final and compact: one shared `NativeElementContext` reference plus one primitive renderer identity. Do not add a per-element runtime reference, geometry scratch fields, relation fields, arrays, maps, locks, atomics, tasks, or lifecycle registry slots.
- One `NativeElementContext` belongs to one `RuntimeInstance` and renderer host. It directly owns the reusable primitive rectangle, offset, and related-element sinks and one shared read-kind guard. Reject nested cross-kind reads and stale sink use.
- A successful rectangle, offset, or related-element host read writes exactly one result synchronously. A false result writes none. The host must not retain a sink, invoke generated language code, or schedule another callback while a sink is active.
- The renderer owns the stable public-wrapper store. `createElement(identity)` must return the same wrapper for the same exact mounted identity, allocate exactly one wrapper on a cache miss, and reject wrappers belonging to another context or identity. Do not add a core-owned generic map or boxed identity.
- Element relation getters resolve the renderer's current parent, first/last child, and previous/next sibling identities through that same public-wrapper store. The common cache-hit path allocates nothing; first exposure may allocate only the one required stable `ReactNativeElement` wrapper.
- `childElementCount` is a nonnegative allocation-free scalar read. An unavailable relation snapshot, absent relation, stale generation, or disconnected element produces null related-element getters and a zero count. Never let stale identities alias reused renderer slots.
- This slice is element-only. Node-level parent/sibling APIs, text nodes, `childNodes`, `children`, `NodeList`, and `HTMLCollection` require separate reached contracts; do not approximate them by aliasing every node to `ReactNativeElement`.
- `getBoundingClientRect()` includes transforms and returns one new non-live `DOMRect`. `offsetWidth`, `offsetHeight`, `offsetTop`, and `offsetLeft` use ECMAScript `Math.round`, including negative zero, NaN, and infinities.
- `offsetParent` returns the renderer's exact published parent wrapper. A hidden renderer root, unavailable offset, stale generation, or missing public wrapper returns null while a root-relative successful snapshot may still expose top and left.
- Client and scroll metric getters are raw allocation-free scalar reads. Do not round or normalize fractions, signed zero, NaN, infinities, or negative values in the core.
- Renderer identities are opaque generation-safe `long` values. They are never exposed as JavaScript numbers and never resolved through a core-owned generic map.
- The renderer owns connectivity, tag names, ids, public-instance lookup, committed-layout timing, element relations, offsets, transforms, coordinate conversion, and platform view lifecycle. The core must not retain an Android `View` or poll layout.
- Callback-based `measure`, `measureInWindow`, `measureLayout`, node collections, events, focus, pointer capture, scroll mutation, text content, and native property mutation require separate reached contracts and conformance.
- Do not add Android, React Native implementation, reflection, networking, serialization, generic collection, executor, Future, coroutine, Handler, or `Runnable` dependencies to production.

Run `./scripts/test-native-elements.sh` and `./scripts/test-native-element-relations.sh` before every relation change.
