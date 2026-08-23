# 0006 — Fuse buffered Fetch completion with owner delivery and keep transport semantics separate

Status: accepted for the first `web-fetch-core` slice.

## Context

The runtime now has owner-confined Promise/microtask semantics, `PlatformPromise`, Web events, and
AbortSignal. Fetch is the first reached Web capability that combines all of them. A transport such
as OkHttp may complete on a worker thread, but Fetch-visible objects and Promise settlement must
remain on the owning `RuntimeInstance`.

It would be easy to hide incorrect behavior behind Java APIs: `java.net.URL`/`URI` do not define the
WHATWG URL parser, Java futures do not define Promise ordering, and OkHttp request/response objects
do not define Fetch headers, body state, or abort semantics.

## Decision

The first Fetch profile is deliberately buffered and explicit. `web-fetch-core` provides:

```text
Fetch
Request
Response
Headers
FetchTransport
FetchTransportRequest
FetchTransportResponse
FetchTransportCallback
FetchTransportCall
```

The current request surface accepts absolute `http://` and `https://` strings only. It does not
claim complete WHATWG URL parsing, CORS, credentials, cache modes, redirect modes, streams, service
workers, or browser cookie policy. Those shapes remain separate features and must not be silently
approximated with `java.net.URL`, `java.net.URI`, or transport defaults.

Request and response bodies are buffered `byte[]` values in this slice. The transport boundary owns
I/O; it receives and returns immutable transport-safe snapshots with no owner-confined Web object.

## Fused operation

One `FetchOperation` is simultaneously:

```text
returned JsPromise
+ FetchTransportCallback
+ AbortAlgorithm
+ admitted RuntimeTask
```

A worker transport callback stores a completed response or failure under the operation monitor and
admits the same object to the runtime ingress queue. It never settles `JsPromise` or dispatches
TypeScript directly. An AbortSignal abort algorithm competes through the same first-completion
state, cancels the transport call, captures the exact specialized abort reason, and admits the same
host task.

Therefore the common network path adds no Future, coroutine continuation, callback Runnable, or
separate Promise resolver object.

## Ordering

A transport callback, including a synchronous callback invoked from `FetchTransport.start`, does not
settle the returned Promise inline. It queues one host completion:

```text
transport completion
    -> claim FetchOperation
    -> runtime owner admission
    -> FetchOperation.execute
    -> settle Fetch Promise
    -> Promise reactions drain as microtasks
```

Abort follows the same later-host-task settlement path. If response/failure wins the completion token
before abort, that completion remains authoritative. If abort wins first, later transport callbacks
are ignored and the transport call is cancelled.

## Headers and request profile

Header names are lower-cased after HTTP-token validation. Values reject NUL/CR/LF and trim HTTP
leading/trailing whitespace. `append`, `set`, `delete`, `has`, and combined `get` are supported.
Network response headers are immutable.

Standard methods are normalized to upper case, forbidden CONNECT/TRACE/TRACK are rejected, and
GET/HEAD bodies are rejected synchronously. Request bodies and transport snapshots are copied so
caller mutation cannot race a worker transport.

## Response body profile

`Response.arrayBuffer()` and `Response.text()` are one-shot body consumers. `bodyUsed` becomes true
synchronously; fulfillment/rejection happens after a microtask hop. A second consumption returns a
rejected Promise. `arrayBuffer()` returns an independent `byte[]` copy. `text()` uses UTF-8 with
replacement behavior supplied by the JDK decoder; a dedicated Encoding module may later replace
that implementation only with conformance evidence.

Streaming `ReadableStream`, cloning, form-data decoding, blob projection, and JSON parsing are not
claimed by this slice.

## Network errors

Transport failures reject with the runtime's Java representation of JavaScript `TypeError` and
retain the platform failure as its Java cause for diagnostics. HTTP error statuses are ordinary
fulfilled Response values.

Abort rejects with the AbortSignal's exact reason payload, including number, boolean, or reference.

## Transport contract

`FetchTransport.start` runs on the runtime owner and may invoke its callback from any thread. The
returned `FetchTransportCall.cancel()` must be thread-safe and idempotent. Transport callbacks must
publish fully-owned, transport-safe snapshots; an OkHttp adapter must not expose a live
`ResponseBody` that requires owner-thread reads.

The future OkHttp module is replaceable transport plumbing. It does not own Fetch Promise ordering,
Headers mutation rules, abort reason semantics, or body-consumption state.

## Rejected alternatives

- `CompletableFuture`/coroutines as the Fetch Promise: wrong scheduler contract and extra objects.
- one Android `Runnable` per network callback: bypasses the coalesced runtime ingress queue.
- `java.net.URL` or `java.net.URI` as the public URL semantics: not WHATWG compatible.
- exposing OkHttp objects directly as Request/Response: transport policy would leak into Web API
  semantics.
- settling Fetch inline for synchronous transports: changes observable Promise ordering.
- worker-thread abort event or Promise settlement: violates owner confinement.

## Required evidence

Permanent tests and structural gates must prove:

- header normalization, validation, mutation, and immutable response guards;
- request body copying and GET/HEAD restrictions;
- a synchronous transport completion does not settle inline;
- a foreign response settles and runs reactions only on the runtime owner;
- an already-aborted request never starts transport;
- abort after start cancels transport and preserves the exact reason;
- network failure rejects with TypeError rather than fulfilling an error status object;
- body consumers set `bodyUsed` synchronously and settle after a microtask hop;
- second body consumption rejects;
- response-versus-abort is first-completion-wins;
- runtime shutdown cancels an undelivered Fetch operation;
- `FetchOperation` is the Promise, transport callback, abort algorithm, and RuntimeTask, never a
  Runnable;
- no CompletableFuture, coroutine runtime, Android Handler, scheduled executor, or java.net URL
  parser enters `web-fetch-core`.
