# 0007 — Buffer OkHttp responses at the transport boundary

Status: accepted for the first `web-fetch-okhttp` slice.

## Context

Decision 0006 defines Fetch-visible Promise ordering, abort reasons, headers, and body state in
`web-fetch-core`. Android still needs a concrete HTTP transport. OkHttp supplies asynchronous calls,
redirect handling, HTTP framing, connection reuse, TLS, decompression, and cache integration, but its
`Call`, `Request`, `Response`, and `ResponseBody` objects are not Web API objects and must not cross
into owner-confined language state.

A live `ResponseBody` is especially unsafe as a handoff value: it owns I/O resources, may only be read
once, and could otherwise defer blocking transport work to the runtime owner. A separate executor,
Future, coroutine, or Android `Handler` in the adapter would also create a second scheduling policy
beside `RuntimeInstance`.

## Decision

Add `web-fetch-okhttp` with one public adapter:

```text
OkHttpFetchTransport(okhttp3.Call.Factory)
```

The constructor accepts an explicit `okhttp3.Call.Factory`. The application owns client policy and
lifecycle; the adapter does not allocate a process-global `OkHttpClient` or choose cookies, caching,
proxying, authentication, TLS, redirects, or dispatcher policy.

`start` maps the immutable `FetchTransportRequest` snapshot to an OkHttp request. Header entries are
added in order so duplicate values are preserved. The buffered request body is copied by the Fetch
core before this boundary. For methods that OkHttp requires to carry a body, including `QUERY`,
an absent Fetch body is represented by `RequestBody.EMPTY`; GET and HEAD remain bodyless because the
core rejects those bodies before transport mapping.

## Fused callback and cancellation handle

One private `OkHttpFetchCall` object is simultaneously:

```text
okhttp3.Callback
+ FetchTransportCall
```

It retains the exact OkHttp `Call`; `cancel()` delegates to that call and inherits OkHttp's
thread-safe, idempotent cancellation contract. There is no second cancellation token, callback
`Runnable`, Future, coroutine continuation, or executor task.

OkHttp may invoke the callback on any thread. The adapter only creates a transport-safe snapshot and
calls `FetchTransportCallback`. `FetchOperation` remains responsible for first-completion wins,
owner admission, Promise settlement, abort-reason preservation, shutdown discard, and microtask
ordering.

## Buffered response ownership

`onResponse` owns and closes the OkHttp `Response`. Before publishing completion it:

1. copies every response header entry in order;
2. reads the complete `ResponseBody` into a byte array on the OkHttp callback thread;
3. records the final request URL and whether the prior-response chain contains an HTTP redirect;
4. creates one immutable `FetchTransportResponse`; and
5. closes the live OkHttp response/body before calling `onResponse` on the Fetch transport callback.

A body-read or snapshot-construction failure closes the response and is reported through
`onFailure`. The Fetch core converts that transport failure to the selected Java representation of
JavaScript `TypeError`. Informational status codes are not exposed as final Fetch responses by this
buffered profile; an unexpected final status outside 200–599 is treated as a transport failure.

This slice deliberately buffers. Streaming requires a later decision that defines backpressure,
owner admission, cancellation, lifetime, and allocation behavior rather than leaking `ResponseBody`
as an approximation of `ReadableStream`.

## Dependency profile

The module compiles against the Java 8 API surface and the current OkHttp 5 line. OkHttp remains an
edge dependency and does not enter `runtime-core`, `web-events`, or `web-fetch-core`. Deterministic
conformance uses test-only `okhttp3` API doubles so no real network, DNS, TLS, clock, or dispatcher is
part of the semantic gate.

## Rejected alternatives

- A module-owned singleton `OkHttpClient`: hides application networking and lifecycle policy.
- Returning a live `ResponseBody`: moves I/O and resource lifetime onto the language owner.
- `Call.execute()` on a worker pool: adds a scheduler and one task per request.
- `CompletableFuture` or coroutines: wrong Promise scheduler and extra completion objects.
- Settling the Fetch Promise from OkHttp's callback thread: violates owner confinement.
- Translating final URLs with `java.net.URL` or `URI`: leaks non-WHATWG URL semantics.
- Base64 response transport: adds avoidable binary expansion and copies.

## Required evidence

Permanent tests and structural gates must prove:

- URL, method, duplicate headers, and request bytes are mapped from immutable snapshots;
- a body-required method with no Fetch body receives an explicit zero-length OkHttp body;
- response bytes and duplicate headers are copied before the live response is closed;
- the final URL and redirected flag are derived from OkHttp's completed response chain without
  misclassifying authentication or retry follow-ups;
- a foreign OkHttp callback leaves the Fetch Promise pending until runtime owner delivery;
- body-read and unsupported-final-status failures close resources and reject as network errors;
- AbortSignal cancellation reaches the exact OkHttp call and a late callback cannot win;
- runtime shutdown cancels the exact OkHttp call for a queued-but-undelivered Fetch completion;
- one adapter object is both `okhttp3.Callback` and `FetchTransportCall`, never `Runnable`; and
- no Future, coroutine scheduler, Android Handler, executor service, or `java.net` URL parser enters
  `web-fetch-okhttp`.
