# Working in web-fetch-core

`web-fetch-core` owns owner-confined Fetch objects, immutable request/response body snapshots, abort
ordering, body consumption, and the transport SPI. Transport adapters own I/O only.

- Validate URL, method, headers, signal, and body runtime before expensive body extraction.
- GET and HEAD reject every present body, including empty strings and empty URLSearchParams values.
- Request construction captures exactly one immutable `BufferedBodySnapshot`; Request and
  FetchTransportRequest share that snapshot reference.
- The transport-facing `copyBody()` is the one defensive full-array copy. Do not insert another
  Request-to-transport clone.
- String BodyInit uses `Utf8Codec.encode` directly and infers `text/plain;charset=UTF-8`.
- URLSearchParams BodyInit uses `copyFormEncodedBytes()` directly and infers
  `application/x-www-form-urlencoded;charset=UTF-8`. Never route it through `toString()`, a charset
  wrapper, or a generic body-adapter object.
- An explicit Content-Type header wins over every inferred type.
- `Response.formData()` marks the body used synchronously and parses only at the Promise-backed
  microtask. Dispatch by extracted MIME essence: use the shared URL form-byte parser or the bounded
  multipart parser. Unsupported MIME, missing boundaries, malformed framing, and parser-limit
  excesses reject with `JsTypeError`.
- Public Fetch objects and parsed FormData/File results remain owner-confined. Transport snapshots
  contain no RuntimeInstance, URL, Blob, FormData, URLSearchParams, callback, stream, or other mutable
  owner object.
- Preserve the fused FetchOperation and one-shot Response body state. Do not add Futures, coroutines,
  executors, Android Handlers, callback Runnables, growing streams, regex MIME parsers, or base64.

Run `./scripts/test-fetch.sh` and `./scripts/test-fetch-okhttp.sh` before every change.
