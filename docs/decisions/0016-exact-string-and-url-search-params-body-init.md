# 0016 — Encode string and URLSearchParams BodyInit values directly into immutable snapshots

Status: accepted for the remaining buffered Request BodyInit adapters.

## Context

Decisions 0014 and 0015 introduced immutable buffered body snapshots and integrated Blob, File, and
FormData with Fetch. Reached programs also pass scalar strings and `URLSearchParams` as Request
bodies. Treating those values as generic adapters would add one wrapper object per Request. Encoding
`URLSearchParams` with `params.toString().getBytes(...)` would additionally allocate a complete
intermediate String, one or more per-entry UTF-8 arrays, and inherit JVM charset behavior.

The Request boundary already has the right ownership point: URL, method, headers, signal, and body
runtime are validated on the owner, then one immutable snapshot is shared with
`FetchTransportRequest`. The new adapters should preserve that copy path rather than introducing a
parallel body representation.

## Decision

Add named Request factories for both string and URL object inputs:

```text
Request.withStringBody(...)
Request.withSearchParamsBody(...)
```

A private primitive body-kind tag selects the existing byte array, `BufferedBodySource`, string, or
`URLSearchParams` extraction path. The tag and original reference are passed directly into Request
construction; no adapter, union wrapper, supplier, lambda, or resolver object is allocated.

Request normalizes and validates the method before body extraction. A GET or HEAD request therefore
rejects even an empty string or empty parameter list before UTF-8 encoding. A URLSearchParams value
must belong to the same `RuntimeInstance` and is checked before serialization.

### String bodies

A string body is converted directly with `Utf8Codec.encode`, which applies the selected scalar-value
replacement behavior and allocates one exactly sized byte array. Request transfers that fresh array
into `BufferedBodySnapshot.fromOwnedBytes` and infers:

```text
text/plain;charset=UTF-8
```

### URLSearchParams bodies

`URLSearchParams.copyFormEncodedBytes()` exposes a fresh exact
`application/x-www-form-urlencoded` payload. Its serializer:

1. computes the largest raw UTF-8 component length without allocating component arrays;
2. allocates one reusable scratch array of that size;
3. reuses the scratch array to compute the exact escaped output length;
4. allocates one final ASCII payload; and
5. reuses the same scratch array while writing the final payload.

No intermediate serialized String, StringBuilder on the body path, per-entry byte array, growing
output stream, charset wrapper, or generic adapter object is created. The output preserves tuple
order, duplicate names, plus-for-space behavior, uppercase percent escapes, and exact UTF-8 scalar
conversion. Request infers:

```text
application/x-www-form-urlencoded;charset=UTF-8
```

An explicit caller `Content-Type` remains authoritative for both body kinds.

## Snapshot and transport ownership

Both adapters run once during Request construction. Subsequent mutation of the source
URLSearchParams object cannot affect the Request. Request and FetchTransportRequest share only the
immutable `BufferedBodySnapshot`; the public transport `copyBody()` remains the single defensive
full-array copy at the untrusted transport boundary.

Empty strings and empty parameter lists still represent present zero-byte bodies. They receive their
inferred Content-Type and remain forbidden on GET and HEAD.

## Performance consequences

The common string path allocates one payload array and the existing snapshot object. The common
URLSearchParams path allocates one reusable scratch array, one exact payload array, and the existing
snapshot object regardless of entry count. There is no per-entry body-adapter allocation and no full
payload copy between Request and FetchTransportRequest.

This slice does not change `URLSearchParams.toString()`, whose public String result necessarily has a
separate representation. It adds a dedicated byte boundary so Fetch does not pay for that result.

## Profile limits

- Java `byte[]` remains the selected typed-array/ArrayBuffer transport ABI until compiler lowering
  supplies additional view adapters.
- Streaming bodies and request duplex remain unreached.
- `Response.formData()` and multipart or form-urlencoded parsing remain a separate decision with
  explicit size, malformed-input, and allocation limits.
- Request and Response cloning remain deferred.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- exact string UTF-8 output, including lone-surrogate replacement;
- exact URLSearchParams form bytes, tuple order, duplicates, spaces, percent escaping, and Unicode;
- URLSearchParams output arrays are independent and Request snapshots survive later mutation;
- exact inferred Content-Type values and explicit-header precedence;
- empty bodies remain present and GET/HEAD rejection occurs before extraction;
- cross-runtime URLSearchParams values are refused;
- Request references `Utf8Codec.encode` and `URLSearchParams.copyFormEncodedBytes` directly;
- the direct form-byte method references `Utf8Codec.encodeInto` but no intermediate StringBuilder or
  TextEncoder output path; and
- no growing stream, JVM charset wrapper, base64, Future, coroutine, executor, Android Handler, or
  Runnable enters the selected body path.
