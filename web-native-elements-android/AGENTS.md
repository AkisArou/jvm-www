# Android native element host rules

- The adapter is an owner-confined committed-layout table, not an Android `View` registry and not a React Native shadow-tree implementation.
- Capture one exact `Looper` at construction. Renderer publication and Web reads must happen on that same Looper; do not add a `Handler`, posting, locking, atomics, or foreign-thread reads.
- Keep element identities positive, opaque, and generation-safe. A released slot may be reused only under a new identity; stale wrappers must remain detached forever.
- Store committed state in compact primitive/reference arrays. Do not allocate one host-side node, map entry, tuple, rectangle, callback, or wrapper per measurement.
- Publish transformed and untransformed rectangles together in one `commitLayout` call so a read never combines two rectangle revisions.
- Publish client width/height, border top/left, scroll position, and scroll size together in one `commitClientAndScrollMetrics` call. Scalar getters resolve one identity and return one primitive; unavailable metrics return positive zero.
- `measureBoundingClientRect` writes directly to the caller's reusable `NativeElementRectSink` and allocates nothing. Metric commits and reads must also remain allocation-free.
- Preserve unrestricted-double values, including NaN, infinities, negative dimensions, fractions, and signed zero. Do not apply Java or CSS rounding in the Android table.
- Do not retain or expose `android.view.View`, `android.graphics.Matrix`, renderer shadow nodes, JNI handles, or platform rectangles.
- Closing the host invalidates all identities and releases metadata arrays. Normal Web reads after close observe detached defaults; renderer mutations fail.

Run `./scripts/test-native-elements-android.sh` before every change.
