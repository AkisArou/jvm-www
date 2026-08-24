# 0014 — Keep buffered Web bodies immutable, segmented, and exact-size at flattening boundaries

Status: accepted for the first `web-bodies` slice.

## Context

Fetch now has canonical URL semantics and a buffered transport boundary. Reached programs next need
`Blob`, `File`, and `FormData`. These APIs combine immutable bytes, reusable reads, metadata,
ordered duplicate form entries, and multipart serialization. A naive implementation based on
`ByteArrayOutputStream`, a generic list of boxed part objects, repeated concatenation, base64, or a
stream wrapper per read would add avoidable allocation and copy costs to common mobile paths.

The selected profile is deliberately buffered. Streaming bodies, disk-backed files, object URLs,
multipart parsing, and `FileReader` remain separate slices with their own lifetime and scheduling
contracts.

## Decision

Add `web-bodies` with:

```text
Blob
BlobBuilder
File
FormData
BufferedBodySource
BufferedBodySnapshot
MultipartBoundarySource
```

All public body objects are confined to one `RuntimeInstance`. `BufferedBodySnapshot` is the only
transport-safe boundary value; it owns an immutable byte array and an optional inferred content
type.

### Segmented immutable Blob storage

A Blob stores parallel arrays of immutable byte segments, offsets, and lengths plus one `long` total
size. Caller-provided byte arrays are copied once. Appending another Blob shares its immutable
segments rather than copying their contents. `slice()` creates an exact segment table over the
selected range and shares the underlying byte arrays.

`BlobBuilder` is the compiler-facing heterogeneous `BlobPart` ABI. It has specialized
`appendBytes`, `appendText`, and `appendBlob` operations, grows only its compact segment tables, and
is consumed once by `build` or `buildFile`. It avoids one wrapper object per part.

A contiguous `byte[]` is allocated only when `arrayBuffer()`/`bytes()`, a body snapshot, or another
explicit flattening boundary needs one. The destination size is known first and each segment is
copied exactly once with `System.arraycopy`.

`Blob.arrayBuffer()`, `Blob.bytes()`, and `Blob.text()` return `JsPromise` objects and settle through
the runtime microtask queue. Blob reads are reusable; they do not set Fetch's one-shot `bodyUsed`
state. Text decoding uses the shared Web UTF-8 decoder.

### File metadata

`File` extends `Blob` without copying its immutable bytes. Constructor names use scalar-value-string
conversion, types use Blob ASCII validation and lowercasing, and `lastModified` is stored as a
primitive `long`. Constructor-created `webkitRelativePath` is the empty string.

### Ordered FormData entries

`FormData` uses parallel arrays for names, values, and a primitive kind tag. Entries remain ordered,
duplicate names are preserved, `set` replaces the first and removes later entries in one compaction
pass, and `delete` compacts in place. Values are scalar strings or `File` objects. Appending a plain
Blob creates the required `File` named `blob`; an existing File keeps its identity unless a filename
override is supplied.

### Exact-size multipart serialization

FormData extraction obtains a fresh boundary from `MultipartBoundarySource`. The default uses one
random UUID, providing more than the required boundary entropy without module-owned mutable state.
Tests inject a deterministic source.

The multipart serializer makes two passes: first computing the exact byte length, then writing one
final array. It normalizes field names and string values to CRLF, percent-escapes CR, LF, and quote in
field names and filenames, emits file content types (or `application/octet-stream`), preserves entry
order, and copies Blob segments directly into the final array. It never uses a growing output stream,
base64, a charset wrapper, or an intermediate flattened File array.

The shared Encoding module exposes a small `Utf8Codec` facade so capability modules can calculate and
write exact UTF-8 output without allocating a `TextEncoder` object or inheriting JVM charset
semantics.

## Profile limits

- Blob `endings: native` is unreached; the implemented builder has transparent string endings.
- ReadableStream, FileReader, disk-backed files, object URLs, and body transfer/serialization are not
  approximated.
- Multipart parsing and `Response.formData()` are deferred.
- The buffered profile rejects bodies whose contiguous representation exceeds Java `byte[]` limits.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- byte inputs are copied once and later caller mutation is invisible;
- Blob concatenation, type normalization, UTF-8 scalar conversion, slicing, reusable reads, and
  microtask settlement;
- File name/type/lastModified behavior and Blob inheritance;
- ordered FormData append/get/getAll/set/delete behavior and Blob-to-File conversion;
- exact multipart bytes, CRLF normalization, header escaping, filename/type handling, and deterministic
  test boundaries;
- immutable snapshots, owner confinement, and cross-runtime refusal;
- compact segment and entry tables rather than generic maps/lists or per-part wrappers; and
- absence of ByteArrayOutputStream, JVM charset wrappers, base64, futures, coroutines, Android
  schedulers, executors, and Runnable jobs from `web-bodies`.
