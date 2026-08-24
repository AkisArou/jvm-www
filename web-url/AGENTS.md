# Working in web-url

`web-url` owns the selected WHATWG URL and URLSearchParams parsing and serialization algorithms.

- Never delegate semantic parsing, resolution, form encoding, or host handling to `java.net`, JVM
  charset aliases, or platform URL helpers.
- URLSearchParams preserves ordered duplicate tuples and owner confinement.
- `copyFormEncodedBytes()` is the Fetch body snapshot boundary. It must allocate no intermediate
  serialized String, no per-entry UTF-8 arrays, and no generic adapter objects.
- Keep its exact-size two-pass writer: one reusable maximum-component UTF-8 scratch array and one
  final ASCII payload array.
- Preserve plus-for-space behavior, uppercase percent escapes, scalar-value conversion, and the form
  percent-encode set exactly.
- Public `toString()` and the byte snapshot may use different representations but must remain
  byte-for-byte equivalent after ASCII conversion.
- Do not add Futures, coroutines, executors, Android Handlers, or Runnable jobs.

Run `./scripts/test-url.sh` and the applicable Fetch gates before every change.
