# Android native element host rules

- The adapter is an owner-confined committed renderer table, not an Android `View` registry and not a React Native shadow-tree implementation.
- Capture one exact `Looper` at construction. Renderer publication and Web reads must happen on that same Looper; do not add a `Handler`, posting, locking, atomics, or foreign-thread reads.
- Keep element identities positive, opaque, and generation-safe. A released slot may be reused only under a new identity; stale wrappers and stale committed parent identities must never resolve to the replacement occupant.
- Store committed state in compact primitive/reference arrays. The `ReactNativeElement[]` table retains only the already-required public wrapper for exact object identity; do not allocate another host-side node, map entry, tuple, rectangle, callback, or wrapper per measurement.
- Publish transformed and untransformed rectangles together in one `commitLayout` call. Publish client/scroll metrics together in one call. Publish offset parent, top, and left together in one `commitOffset` call.
- A zero offset-parent identity is an Android-adapter-only sentinel for the inaccessible renderer root. Every nonzero committed parent must be a distinct still-mounted exact identity.
- `measureBoundingClientRect` and `measureOffset` write directly to the caller's reusable primitive sink and allocate nothing. Scalar client and scroll getters perform one direct committed-table load.
- Unmount must clear metadata, the public-wrapper reference, and the committed parent identity before slot reuse. Public wrappers retained by callers remain disconnected.
- Do not retain or expose `android.view.View`, `android.graphics.Matrix`, renderer shadow nodes, JNI handles, platform rectangles, sparse maps, or generic collections.
- Closing the host invalidates all identities and releases metadata, public-wrapper, parent, and primitive arrays. Normal Web reads after close observe detached defaults; renderer mutations fail.

Run `./scripts/test-native-elements-android.sh` before every change.
