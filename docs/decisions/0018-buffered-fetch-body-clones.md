# 0018 — Clone buffered Fetch bodies by sharing immutable snapshots

Status: accepted for Request/Response clone and Request Body readers.

## Context

Decisions 0014 through 0017 established one immutable `BufferedBodySnapshot` per buffered request or
response, one-shot Body consumption, exact BodyInit extraction, and bounded form parsing. The public
Fetch objects still lack `Request.clone()`, `Response.clone()`, and the Body reader methods on Request.
A direct implementation that calls `copyBytes()` while cloning would make every clone proportional to
payload size and would multiply large mobile upload/download buffers. Duplicating the Response reader
implementation inside Request would also create two independently evolving microtask and error paths.

The Fetch Standard rejects cloning an unusable body, gives each clone an independent body branch,
creates a dependent AbortSignal for a Request clone, and keeps header objects distinct. In this
buffered profile an immutable snapshot can represent the two body branches without a stream tee or a
payload copy.

## Decision

### Shared body-read microtask

Add one package-private `FetchBodyReadPromise` that is simultaneously:

```text
returned JsPromise
+ queued RuntimeTask
+ retained immutable body snapshot until execution
```

Request and Response use that object for `arrayBuffer()`, `bytes()`, `text()`, `blob()`, and
`formData()`. It performs MIME extraction only when the body is usable, so repeated calls after
consumption do not reparse headers. The object releases its snapshot on execution or discard and is
never a platform Runnable.

Request gains the complete selected buffered Body surface and its own `bodyUsed` flag. A present body
becomes used synchronously when a read starts or when Fetch creates the transport snapshot. A second
read, second Fetch, or clone then rejects or throws through the existing TypeError boundary.

A Request whose body is null follows the Body mixin distinction between null and an empty present
body: reads use one shared immutable empty snapshot, `bodyUsed` remains false, repeated reads are
allowed, and cloning remains allowed. Empty string, empty URLSearchParams, empty Blob, and empty
FormData bodies remain present bodies and are one-shot.

### Request clone

`Request.clone()`:

- throws when a present body is already used;
- copies canonical URL/method metadata without reparsing the URL;
- creates a new mutable Headers object with copied entries;
- creates a dependent AbortSignal with `AbortSignal.any(runtime, source)` when this Java profile has a
  non-null source signal;
- shares the immutable `BufferedBodySnapshot` reference; and
- starts with independent `bodyUsed = false`.

The current Java ABI permits a null signal for Requests created without cancellation. Cloning such a
Request preserves null rather than allocating a never-aborted signal solely for WebIDL identity. Once
compiler bindings make Request.signal unconditionally observable, they may supply the always-present
signal object at construction without changing the clone/body representation.

### Fetch ownership claim

`FetchTransportRequest` calls `Request.claimBodyForTransport()` before allocating URL/header snapshot
arrays. This marks a present body used synchronously and rejects a reused Request before transport or
operation allocation. Request and transport still share the same immutable snapshot; the public
transport `copyBody()` remains the one defensive full-array copy at the untrusted I/O boundary.

### Response clone

`Response.clone()`:

- throws after body consumption has started;
- copies URL, status, status text, redirect state, and an independent immutable Headers object;
- shares the immutable body snapshot; and
- starts with independent bodyUsed state.

Each branch can therefore be consumed once, including bounded form parsing and zero-copy multipart
File views, without duplicating the complete response payload.

## Performance consequences

Clone cost is proportional to header metadata, not body size. No clone path calls `copyBytes()`,
`snapshot()`, BodyInit extraction, multipart serialization, or URL parsing. The only body-read object
is the Promise that is already required for observable asynchronous settlement. Null-body reads reuse
one immutable empty snapshot.

The buffered representation avoids `ReadableStream.tee`, queue pairs, chunk wrappers, backpressure
state, and a worker scheduler. Streaming bodies remain a separate profile where teeing and lifetime
policy must be designed explicitly.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- Request and Response clones have distinct object and Headers identity;
- Request clones receive a distinct dependent signal and preserve the exact abort reason;
- clones share body bytes while each branch consumes independently;
- clone throws immediately after a read or Fetch has claimed a present body;
- Fetch marks Request.bodyUsed synchronously and refuses reuse before another transport start;
- Request exposes all selected Body readers, including direct bounded formData parsing;
- null-body reads are repeatable and do not change bodyUsed;
- one `FetchBodyReadPromise` fuses JsPromise and RuntimeTask for both object types;
- Request/Response clone bytecode contains no body copy, BodyInit extraction, or URL reparsing; and
- no Future, coroutine, executor, Handler, stream tee, growing stream, base64, or Runnable enters the
  buffered clone/read path.
