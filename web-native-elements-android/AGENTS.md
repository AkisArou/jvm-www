# Android native element host rules

- The adapter is an owner-confined committed renderer table, not an Android `View` registry and not a React Native shadow-tree implementation.
- Capture one exact `Looper` at construction. Renderer publication and Web reads must happen on that same Looper; do not add a `Handler`, posting, locking, atomics, or foreign-thread reads.
- Keep element identities positive, opaque, and generation-safe. A released slot may be reused only under a new identity; stale wrappers and stale committed parent or child identities must never resolve to the replacement occupant.
- Store committed state in compact primitive/reference arrays. The `ReactNativeElement[]` table retains only the already-required public wrapper for exact object identity; do not allocate another host-side node, map entry, tuple, rectangle, callback, or wrapper per measurement.
- Publish transformed and untransformed rectangles together in one `commitLayout` call. Publish client/scroll metrics together in one call. Publish offset parent, top, and left together in one `commitOffset` call. Publish the five element relations and child count together in one `commitElementRelations` call.
- Element containment reuses the existing exact-generation parent relation. It adds no Android field, ancestor cache, traversal array, wrapper exposure, or renderer mutation. Stale parent generations stop the walk until the renderer publishes a complete replacement relation.
- The live `children` collection is implemented by walking the existing exact-generation first-child/next-sibling links. Do not add per-parent child arrays, collection snapshots, iterators, or boxed indices to the Android table.
- A zero offset-parent or element-relation identity is an Android-adapter-only sentinel for the inaccessible renderer root or an absent relation. Every nonzero committed relationship must be a distinct still-mounted exact identity.
- `measureBoundingClientRect`, `measureOffset`, and relation reads write directly to the caller's reusable primitive sink and allocate nothing. Scalar client, scroll, and child-count getters perform direct committed-table loads.
- Unmount must clear metadata, the public-wrapper reference, committed parent identity, outgoing element relations, and child count before slot reuse. Public wrappers and collections retained by callers remain detached and cannot alias the replacement generation.
- Do not retain or expose `android.view.View`, `android.graphics.Matrix`, renderer shadow nodes, JNI handles, platform rectangles, sparse maps, generic collections, ancestor sets, or child snapshots.
- Closing the host invalidates all identities and releases metadata, public-wrapper, parent, relation, count, and primitive arrays. Normal Web reads after close observe detached defaults; renderer mutations fail.

Run `./scripts/test-native-elements-android.sh`, `./scripts/test-native-element-relations.sh`, `./scripts/test-native-element-collections.sh`, and `./scripts/test-native-element-containment.sh` before every change.
