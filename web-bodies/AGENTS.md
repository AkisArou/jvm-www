# Working in web-bodies

`web-bodies` owns immutable buffered Blob/File bytes, ordered FormData state, body snapshots, and
multipart serialization for the selected Web Mobile profile.

- Keep every public Blob, File, FormData, and builder owner-confined to one `RuntimeInstance`.
- `BufferedBodySnapshot` is the only worker/transport-safe body handoff. It must contain no runtime,
  callback, stream, or mutable owner object.
- Caller byte arrays are copied once. Immutable Blob segments may be shared across concatenation,
  slices, Files, and response Blob projection.
- Flatten only at an explicit byte-array result or transport snapshot boundary. Compute the exact
  destination length first and copy each segment once.
- FormData uses compact parallel arrays and in-place compaction. Do not replace it with a generic map
  or one wrapper object per entry.
- Multipart encoding is two-pass and exact-size. Never add `ByteArrayOutputStream`, base64, repeated
  concatenation, a charset convenience API, or an intermediate flattened File body.
- Blob reads are runtime microtasks, not platform Runnables, futures, coroutines, or executor work.
- Streaming, disk-backed files, object URLs, multipart parsing, and FileReader require separate
  lifetime and backpressure decisions; do not approximate them here.

Run `./scripts/test-bodies.sh` and the applicable Fetch gates before every change.
