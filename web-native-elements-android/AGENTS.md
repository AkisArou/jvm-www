# Android native element host rules

- The adapter is an owner-confined committed renderer table, not an Android `View` registry and not a React Native shadow-tree implementation.
- Capture one exact `Looper` at construction. Renderer publication and Web reads must happen on that same Looper; do not add a `Handler`, posting, locking, atomics, or foreign-thread reads.
- Keep element identities positive, opaque, and generation-safe. A released slot may be reused only under a new identity; stale wrappers, offset parents, and element relations must never resolve to the replacement occupant.
- Store committed state in compact primitive/reference arrays. The `ReactNativeElement[]` table retains only the already-required public wrapper for exact object identity; do not allocate another host-side node, map entry, tuple, rectangle, callback, or wrapper per read.
- Publish transformed and untransformed rectangles together in one `commitLayout` call. Publish client/scroll metrics together in one call. Publish offset parent, top, and left together in one `commitOffset` call.
- Publish parent element, first and last element child, previous and next element sibling, and child-element count together in one `commitElementRelations` call. A zero relation identity is the adapter-private absent/inaccessible-root sentinel; every nonzero relation must be a distinct still-mounted exact identity.
- The relation table uses one strided `long[]` plus one `int[]` count table. Relation reads validate the selected generation before writing the reusable sink. A stale first or last child makes `childElementCount` unavailable rather than exposing a reused slot.
- Renderer commits must update affected relation snapshots after a tree revision. Inbound stale references are tolerated only as unavailable reads until that publication occurs; they are never followed or rebound by slot alone.
- `measureBoundingClientRect`, `measureOffset`, and related-element reads write directly to caller-owned reusable primitive sinks and allocate nothing. Scalar client, scroll, and child-count getters perform direct committed-table loads.
- Unmount must clear metadata, the public-wrapper reference, outgoing relation identities, child count, and the committed offset parent before slot reuse. Public wrappers retained by callers remain disconnected.
- Do not retain or expose `android.view.View`, `android.graphics.Matrix`, renderer shadow nodes, JNI handles, platform rectangles, sparse maps, or generic collections.
- Closing the host invalidates all identities and releases metadata, public-wrapper, relation, count, parent, and primitive arrays. Normal Web reads after close observe detached defaults; renderer mutations fail.

Run `./scripts/test-native-elements-android.sh` and `./scripts/test-native-element-relations.sh` before every relation change.
