# 0015 — Snapshot buffered Web bodies once at Request construction

Status: accepted for the Fetch/body integration slice.

## Context

Decision 0014 introduced owner-confined `Blob`, `File`, and `FormData` together with an immutable
`BufferedBodySnapshot` boundary. Fetch still stores request bodies as raw `byte[]`, infers no body
content type, and cannot project a response body as a `Blob`. Integrating bodies naively could add a
full-array copy at every layer:

```text
Blob/FormData flatten
  -> Request copy
  -> FetchTransportRequest copy
  -> OkHttp copy
```

It could also retain a mutable owner-confined FormData or Blob into a worker-readable transport
object, which would violate runtime ownership. Response `blob()` must preserve Fetch's one-shot body
state while the resulting Blob itself remains reusable and immutable.

## Decision

`web-fetch-core` has an API dependency on `web-bodies`.

### Request extraction

The existing byte-array constructors remain source-compatible. A named factory avoids Java's null
overload ambiguity and accepts the selected buffered body union:

```text
Request.withBody(runtime, stringURL, method, headers, BufferedBodySource, signal)
Request.withBody(runtime, URL,       method, headers, BufferedBodySource, signal)
```

Construction validates the URL, method, headers, signal, and body runtime before extraction. GET and
HEAD reject a non-null body before calling `snapshot()`, so an invalid request does not perform an
expensive Blob flatten or multipart encoding. A body from another runtime is also rejected before
extraction.

A valid body is snapshotted exactly once during Request construction. This freezes FormData order,
values, filename metadata, and its generated multipart boundary. Later mutation of the source object
cannot affect the Request. If the snapshot supplies a non-empty content type and the copied request
headers do not contain `Content-Type`, Fetch appends the inferred value. An explicit caller header
wins unchanged.

`Request` and `FetchTransportRequest` share the same immutable `BufferedBodySnapshot` object. The
transport request contains no Blob, File, FormData, or other owner-confined value. Its public
`copyBody()` performs the one defensive full-array copy at the untrusted transport boundary. This
removes the former intermediate Request-to-transport byte-array clone while preserving Request reuse
and transport isolation.

### Response consumption

A completed response transfers its freshly copied transport bytes into one
`BufferedBodySnapshot`. `arrayBuffer()` and `bytes()` copy that snapshot once; `text()` copies once and
uses the shared Web UTF-8 decoder. `blob()` creates a Blob that shares the immutable response snapshot
without another full body copy. All methods retain the accepted one-shot Response `bodyUsed` state and
settle through one Promise-backed runtime microtask.

Blob reads remain reusable because the new Blob has its own immutable value semantics after response
consumption.

### MIME type extraction

`Response.blob()` lazily runs Fetch's MIME type extraction only when requested. The parser:

- splits combined Content-Type values without splitting quoted commas;
- parses tokenized type/subtype and ordered parameters;
- skips invalid values and `*/*`;
- implements Fetch's same-essence charset carry rule; and
- serializes the last accepted MIME type.

Parameter storage uses small parallel arrays that grow only when needed. It does not allocate a
`Map`, `ArrayList`, or one object per parameter. The resulting type passes through Blob's existing
ASCII validation and lowercasing boundary.

## Performance and ownership consequences

For a Blob request, the common buffered path is:

```text
segmented Blob --one exact flatten--> Request snapshot
Request/transport snapshot --shared immutable reference-->
transport copyBody() --one defensive copy--> OkHttp RequestBody
```

For FormData, the exact-size multipart encoder directly creates the Request snapshot. There is no
`ByteArrayOutputStream`, repeated concatenation, base64, intermediate File flatten, stream wrapper,
Future, executor, coroutine, Handler, or callback Runnable.

Response `blob()` performs zero additional full-body copies after transport delivery. ArrayBuffer,
bytes, and text necessarily create an independent result copy.

## Profile limits

- Streaming request and response bodies remain unreached.
- `Response.formData()` and multipart parsing remain deferred.
- URLSearchParams and scalar-string BodyInit adapters remain separate compiler/profile integrations.
- Request and Response cloning remain deferred.
- Redirect, credentials, cache, and cookie policy remain outside this body integration.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- Blob and File bodies infer Content-Type while an explicit header wins;
- FormData extraction captures one boundary and is unaffected by later mutation;
- GET/HEAD and cross-runtime rejection happen before snapshot extraction;
- Request and transport values share only `BufferedBodySnapshot`, never owner-confined body objects;
- transport `copyBody()` remains defensive and isolated;
- Response blob/bytes/text follow one-shot body state and microtask ordering;
- response Blob bytes are correct and MIME extraction follows invalid, wildcard, quoted-comma,
  essence-change, and charset-carry cases;
- Response `blob()` calls `Blob.fromSnapshot` rather than a byte-array-copying constructor;
- MIME parameters use compact arrays rather than generic collections; and
- the runtime, Encoding, URL, body, Fetch, and OkHttp gates remain green.
