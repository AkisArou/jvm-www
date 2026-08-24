# Working in web-fetch-core

`web-fetch-core` owns owner-confined Fetch objects, immutable request/response body snapshots, abort
ordering, cloning, and the transport SPI. Transport adapters own I/O only.

- Validate URL, method, headers, signal, and body runtime before expensive body extraction.
- GET and HEAD reject every present body, including empty strings and empty URLSearchParams values.
- Request construction captures exactly one immutable `BufferedBodySnapshot`; Request and
  FetchTransportRequest share that snapshot reference.
- Fetch claims a present Request body synchronously before starting transport. A used Request cannot
  be read, cloned, or fetched again.
- Request and Response clones copy header metadata and share immutable body snapshots. Never call
  `copyBytes`, re-run BodyInit extraction, reserialize FormData, or reparse a URL while cloning.
- Clone bodyUsed state is independent. A Request clone uses a dependent AbortSignal when a source
  signal exists; a null signal in the current Java ABI remains null.
- Null-body Request reads reuse the immutable empty snapshot, remain repeatable, and do not set
  bodyUsed. Empty present bodies remain one-shot.
- `FetchBodyReadPromise` is the shared returned Promise and runtime microtask for Request and Response
  readers. Do not add a resolver, Runnable, future, stream, or per-reader adapter layer.
- The transport-facing `copyBody()` is the one defensive full-array copy. Do not insert another
  Request-to-transport clone.
- String BodyInit uses `Utf8Codec.encode` directly and infers `text/plain;charset=UTF-8`.
- URLSearchParams BodyInit uses `copyFormEncodedBytes()` directly and infers
  `application/x-www-form-urlencoded;charset=UTF-8`. Never route it through `toString()`, a charset
  wrapper, or a generic body-adapter object.
- An explicit Content-Type header wins over every inferred type.
- Public Fetch objects remain owner-confined. Transport snapshots contain no RuntimeInstance, URL,
  Blob, FormData, URLSearchParams, callback, stream, or other mutable owner object.
- Preserve the fused FetchOperation and bounded FormData parsers. Do not add Futures, coroutines,
  executors, Android Handlers, callback Runnables, growing streams, or base64.

Run `./scripts/test-fetch.sh` and `./scripts/test-fetch-okhttp.sh` before every change.
