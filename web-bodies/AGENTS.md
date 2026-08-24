# Working in web-bodies

`web-bodies` owns immutable buffered Blob/File bytes, ordered FormData state, body snapshots,
multipart serialization, and bounded response form-data parsing for the selected Web Mobile profile.

- Keep every public Blob, File, FormData, parser result, and builder owner-confined to one
  `RuntimeInstance`.
- `BufferedBodySnapshot` is the only worker/transport-safe body handoff. It must contain no runtime,
  callback, stream, or mutable owner object.
- Caller byte arrays are copied once. Immutable Blob segments may be shared across concatenation,
  slices, Files, response Blob projection, and parsed multipart File ranges.
- Flatten only at an explicit byte-array result or transport snapshot boundary. Compute the exact
  destination length first and copy each segment once.
- FormData uses compact parallel arrays and in-place compaction. Do not replace it with a generic map
  or one wrapper object per entry.
- Multipart encoding is two-pass and exact-size. Never add `ByteArrayOutputStream`, base64, repeated
  concatenation, a charset convenience API, or an intermediate flattened File body.
- Response multipart parsing is direct-byte and bounded: at most 1024 parts, 64 headers per part,
  64 KiB of headers per part, and a 70-byte boundary. Preserve strict CRLF framing and validate
  header syntax without building a MIME object graph.
- Parsed File bodies use immutable `BlobData.singleView` ranges over the response snapshot. Parsed
  text and header parameters use direct `TextDecoder` range decoding; do not copy whole parts first.
- Blob reads and Fetch body parsing are runtime microtasks, not platform Runnables, futures,
  coroutines, or executor work.
- Streaming, disk-backed files, object URLs, nested multipart bodies, and FileReader require separate
  lifetime and backpressure decisions; do not approximate them here.

Run `./scripts/test-bodies.sh` and the applicable Fetch gates before every change.
