# 0017 — Parse buffered response form data directly with bounded multipart metadata

Status: accepted for the first `Response.formData()` slice.

## Context

Decision 0015 gave Fetch one immutable response body snapshot and one-shot body consumption, but the
selected Body mixin still lacked `formData()`. The Fetch Standard dispatches that method by the
extracted MIME essence: URL-encoded bytes use the URL form parser, while multipart bytes are parsed
into ordered string or File entries. A naive implementation would decode the entire response to a
String, split it repeatedly, allocate a wrapper per header and parameter, flatten every file, or use a
general MIME library whose limits and compatibility rules are outside this runtime.

Multipart input is attacker-controlled. Even in the deliberately buffered profile, metadata parsing
must not permit unbounded part/header object growth or quadratic concatenation.

## Decision

### Shared URL-encoded byte parser

`web-url` exposes two narrow parser contracts:

```text
FormUrlEncodedParser.parse(runtime, bytes, consumer)
FormUrlEncodedConsumer.acceptFormEntry(name, value)
```

The parser reads the supplied byte array immediately and never retains or mutates it. It emits decoded
scalar strings in order. `URLSearchParams` and `FormData` consume the same parser directly, avoiding a
temporary URLSearchParams object and a second entry-list copy.

`FormUrlCodec` decodes unchanged byte ranges through the new `TextDecoder.decode(input, offset,
length)` ABI. It allocates an exact-size temporary array only when plus or valid percent escapes
actually require byte transformation.

### Bounded direct-byte multipart parser

`web-bodies` adds `FormDataParser`. It parses one immutable `BufferedBodySnapshot` directly and uses
fixed profile limits:

```text
maximum parts             1024
maximum headers per part    64
maximum header bytes/part 64 KiB
maximum boundary length     70 bytes
```

The parser scans the byte array without regex, line splitting, a growing stream, or a generic MIME
object graph. It requires CRLF boundary/header framing, one valid `Content-Disposition: form-data`
header with a `name` parameter, and token/quoted-string parameter syntax. Unknown part headers are
ignored after validation. Duplicate Content-Disposition is rejected. `filename*` and other extended
parameter forms are unreached and rejected rather than interpreted.

A part with `filename` becomes a File. Its immutable BlobData is a range view over the already-owned
response snapshot, so the common file path performs no additional full-payload copy. The File type is
the part Content-Type after Blob validation, or `text/plain` when absent. A part without `filename`
is decoded as UTF-8 without BOM stripping, regardless of its Content-Type, and appended as a string.
Order and duplicate names are preserved.

The range view deliberately retains the response byte array while any parsed File remains live. This
trades possible backing-array retention for zero-copy file extraction in the current buffered mobile
profile. A later disk-backed or segmented streaming profile requires a separate lifetime decision.

### Fetch integration

`Response.formData()` marks `bodyUsed` synchronously and settles through the existing Promise-backed
body microtask. It runs Fetch MIME extraction once:

- `application/x-www-form-urlencoded` invokes the shared URL byte parser;
- `multipart/form-data` requires a non-empty boundary and invokes the bounded multipart parser;
- every other essence, missing boundary, or parse failure rejects with `JsTypeError`.

The method consumes the same immutable response snapshot used by bytes, text, and Blob projections.
A second body consumer rejects through the existing one-shot path.

## Performance and ownership consequences

The URL-encoded path allocates only decoded entry strings plus exact percent-decoding arrays for
components that need transformation. The multipart path allocates FormData's compact parallel entry
arrays, decoded names/text values, small escaped-parameter buffers when required, and File metadata.
File payload bytes are not copied.

No ByteArrayOutputStream, regex splitter, Scanner, MIME Message object, Map, per-header collection,
base64 layer, Future, coroutine, executor, Android Handler, or Runnable enters the parser path. All
public results remain owner-confined to the Response runtime; only immutable bytes are read.

## Profile limits

- Multipart parsing is a strict reached subset of RFC 7578 and Fetch's deliberately approximate
  multipart requirement. Transport padding, nested multipart bodies, Content-Transfer-Encoding,
  RFC 2231/5987 extended parameters, and legacy charset selection are rejected or ignored only where
  the selected profile explicitly says so; they are never approximated.
- URL-encoded input always follows the WHATWG UTF-8 form parser; a charset parameter does not select a
  JVM charset.
- Streaming parsing, disk spooling, and `Request.formData()` are separate slices.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- scalar and URLSearchParams request BodyInit adapters remain exact and allocation-bounded;
- URL-encoded Response parsing preserves order, duplicates, plus/percent behavior, UTF-8 replacement,
  and BOM data;
- multipart strings, files, empty filenames, default file types, order, duplicates, and quoted
  boundaries;
- unsupported MIME types, missing boundaries, malformed framing/headers, and profile-limit excesses
  reject asynchronously with TypeError;
- bodyUsed changes synchronously and a second consumer rejects;
- parsed FormData and Files remain owner-confined;
- multipart File construction uses `BlobData.singleView` and TextDecoder range decoding; and
- URL, bodies, Fetch, and OkHttp bytecode contain no growing streams, regex parser, generic metadata
  collections, JVM charset shortcut, base64, scheduler, or Runnable path.
