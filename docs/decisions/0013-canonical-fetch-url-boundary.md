# 0013 — Canonicalize Fetch URLs through the owner-confined Web URL record

Status: accepted for the canonical URL integration slice.

## Context

Decision 0006 deliberately refused to use `java.net.URL` or `java.net.URI` while the Web URL module
was unreached. Decisions 0011 and 0012 now provide owner-confined `URLSearchParams` and a strict
special HTTP(S) `URL` record. Keeping Fetch's former prefix/control-character validator would create
a second URL interpretation path: `new URL(...)`, `new Request(...)`, and the eventual transport
could disagree about case, default ports, dot segments, escaping, credentials, or fragments.

Fetch also crosses an ownership boundary. A public `URL` object is mutable and belongs to one
`RuntimeInstance`, while a transport request must be an immutable value that may be read on a worker
thread. A live `URL` object therefore cannot become part of `FetchTransportRequest`.

## Decision

`web-fetch-core` has an API dependency on `web-url`. `Request` accepts either a string or an
owner-confined `URL`:

```text
Request(runtime, string, ...)
Request(runtime, URL, ...)
Fetch.fetch(runtime, transport, string)
Fetch.fetch(runtime, transport, URL)
```

String inputs are parsed by the selected `URL` implementation. A `URL` input must belong to the same
runtime. Construction snapshots the canonical serialized URL and retains no reference to the mutable
input object, so later URL mutations cannot change an already-created request.

The JavaScript-visible `Request.url` snapshot includes its fragment, matching URL serialization.
The immutable transport snapshot stores a second canonical string with the fragment excluded. Since
all literal `#` characters outside the fragment delimiter are percent-encoded by `web-url`, removing
the first delimiter from the canonical serialization is unambiguous. No owner-confined URL object
crosses the transport boundary.

Fetch request construction rejects URLs with a non-empty username or password. Modern `Request`
construction does not opt into URL credentials; silently handing those credentials to OkHttp would
create a security-sensitive compatibility extension outside the selected profile.

A completed transport response is parsed on the runtime owner through the same URL implementation.
`Response.url` stores its canonical final URL with the fragment excluded. If the transport publishes
a final URL outside the reached HTTP(S) profile, or another response snapshot field cannot be
constructed, the Fetch operation rejects with the selected network `TypeError` and retains the
construction failure as its Java cause. It never strands the returned Promise pending.

## Profile limits

This integration does not invent an environment base URL. Relative string inputs still fail unless a
later global/environment capability defines their base. IDNA, IPv6, legacy IPv4, non-special schemes,
and other URL shapes remain governed by decision 0012 and fail precisely.

Redirect policy remains transport/application policy in the buffered profile. This slice only makes
the initial request target and final response URL use one canonical semantic implementation.

## Ownership and allocation

Parsing and Request construction run synchronously on the runtime owner. A string input allocates the
ordinary temporary `URL` needed to parse it; a `URL` input is read and snapshotted without a clone or
observer wrapper. `FetchTransportRequest` continues to contain only strings, header arrays, and a
copied body. Response canonicalization also runs only after the transport completion is admitted as a
host task.

No `java.net` URL/URI/form helper, JVM charset wrapper, Future, coroutine, executor, Android Handler,
or callback Runnable enters `web-fetch-core`.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- string Request inputs use canonical scheme, host, port, path, query, and scalar encoding;
- `URL` inputs are accepted, same-runtime checked, and snapshotted against later mutation;
- URL credentials are rejected synchronously for both input forms;
- `Request.url` preserves a canonical fragment while the transport target excludes it;
- the Fetch URL overload reaches the same Request path;
- final response URLs are canonicalized and exposed without fragments;
- an invalid transport response URL rejects the returned Promise with network `TypeError` rather
  than leaving it pending;
- transport snapshots contain no owner-confined `URL` reference; and
- the existing Fetch ordering, abort, shutdown, Encoding, and OkHttp gates remain green.
