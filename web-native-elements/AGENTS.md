# Native element rules

- `web-native-elements` implements only the reached read-only React Native element value surface. It is not a browser DOM tree, renderer, layout engine, Android view registry, or mutation API.
- Keep each `ReactNativeElement` wrapper final and compact: one shared `NativeElementContext` reference plus one primitive renderer identity. Do not add a per-element runtime reference, geometry scratch fields, arrays, maps, locks, atomics, tasks, or lifecycle registry slots.
- One `NativeElementContext` belongs to one `RuntimeInstance` and renderer host. It owns the reusable primitive rectangle sink and rejects nested or stale sink use.
- A successful host measurement writes exactly one rectangle synchronously. A false result writes none. The host must not retain the sink, invoke generated language code, or schedule another callback while the sink is active.
- `getBoundingClientRect()` includes transforms and returns one new non-live `DOMRect`. `offsetWidth` and `offsetHeight` exclude transforms and use ECMAScript `Math.round` behavior, including negative zero, NaN, and infinities.
- Renderer identities are opaque generation-safe `long` values. They are never exposed as JavaScript numbers and never resolved through a core-owned generic map.
- The renderer owns connectivity, tag names, ids, native-node lookup, committed-layout timing, transforms, coordinate conversion, and platform view lifecycle. The core must not retain an Android `View` or poll layout.
- Callback-based `measure`, `measureInWindow`, `measureLayout`, tree traversal, events, focus, pointer capture, scroll metrics, text content, mutation, and an Android adapter require separate reached contracts and conformance.
- Do not add Android, React Native implementation, reflection, networking, serialization, generic collection, executor, Future, coroutine, Handler, or `Runnable` dependencies to production.

Run `./scripts/test-native-elements.sh` before every change.
