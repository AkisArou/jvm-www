# 0020 — Map the WebSocket transport SPI directly onto one OkHttp WebSocket

Status: accepted for the first Android/JVM WebSocket transport adapter.

## Context

Decision 0019 introduced an owner-confined browser-shaped WebSocket core. Foreign callbacks publish
transport-safe values, each observable socket event remains a distinct host task, incoming binary
arrays transfer ownership once, and `bufferedAmount` samples the transport only when requested. The
next Android boundary needs to use OkHttp without importing OkHttp scheduling, response objects, or
Okio storage into owner-confined Web state.

OkHttp's WebSocket API is already asynchronous and thread-safe, but its representations do not match
the core exactly:

- creation uses `WebSocket.Factory.newWebSocket(Request, WebSocketListener)`;
- outgoing binary messages require an immutable Okio `ByteString`;
- incoming binary messages arrive as `ByteString`;
- queued application bytes are reported by `WebSocket.queueSize()`;
- `WebSocket.close()` requires a concrete status code; and
- the successful upgrade `Response` is tied to the live socket and must not cross the callback.

A naive adapter could add a listener object plus a separate cancellation/send handle, copy a snapshot
into a byte array before Okio copies it again, retain the upgrade Response, poll queue size, or add an
executor to normalize callbacks. Each would either duplicate large payloads or create a second
scheduler and ownership model.

## Decision

Add `websocket-okhttp` with an `OkHttpWebSocketTransport` constructed from an application-supplied
`okhttp3.WebSocket.Factory`. In ordinary applications the supplied object is the application's
configured `OkHttpClient`. The application continues to own dispatcher, connection pool, TLS, proxy,
authentication, compression, ping interval, and client lifecycle policy.

### One fused bridge

Each connection allocates one `OkHttpWebSocketCall` that:

```text
extends okhttp3.WebSocketListener
implements WebSocketTransportCall
```

The same object receives all OkHttp callbacks and provides `send`, `queueSize`, `close`, and `cancel`
to the WebSocket core. It stores the exact OkHttp WebSocket returned by the factory. Callback identity
is checked by object identity; a callback from another socket is cancelled and reported as a transport
failure.

The binding operation accepts a callback that occurs synchronously inside `newWebSocket()` before the
factory returns, provided the callback and returned socket are the same object. No pending-callback
wrapper or temporary callback queue is introduced.

### Handshake mapping

The adapter builds one OkHttp Request from the canonical `ws:` or `wss:` URL supplied by the core.
When protocols were offered, it emits one `Sec-WebSocket-Protocol` header in caller order, separated
by comma plus space. OkHttp owns all standard upgrade headers.

On `onOpen`, the adapter requires HTTP status 101 and scans the response headers synchronously. It
publishes only two Strings:

```text
selected Sec-WebSocket-Protocol, or ""
combined Sec-WebSocket-Extensions values, or ""
```

No OkHttp Response, Headers, WebSocket, or response body crosses into the core. The response is not
closed by the adapter because it is the live successful upgrade response owned by OkHttp.

### Binary ownership and copies

Incoming Okio `ByteString` values are immutable but cannot be transferred as the core's byte-array ABI.
The adapter performs one `ByteString.toByteArray()` copy and immediately transfers ownership of that
fresh array to `WebSocketTransportListener.onBinaryMessage`. The core then exposes that array directly
for `binaryType = "arraybuffer"` or wraps it in Blob storage without another full copy.

Outgoing snapshots are already immutable. `BufferedBodySnapshot` therefore exposes a fresh read-only
`ByteBuffer` view over its bytes. The adapter calls:

```text
ByteString.of(snapshot.asReadOnlyByteBuffer())
```

Okio copies the buffer once into its immutable ByteString. The adapter never calls
`BufferedBodySnapshot.copyBytes()`, avoiding a snapshot clone followed by a second ByteString copy.
The ByteBuffer view has independent position/limit state and cannot mutate or expose the backing array.

### Queue, close, and failure mapping

`getQueuedByteCount()` delegates directly to `WebSocket.queueSize()` and allocates no observer. The
core remains responsible for combining transport-queued bytes with bytes discarded after closing.
There is no polling timer or callback per send.

The core uses close code `0` to indicate that JavaScript supplied no code. OkHttp requires a concrete
code, so the adapter maps `0` to normal closure code `1000`. Explicit core-validated codes and reasons
pass through unchanged.

`onClosing` publishes the peer-closing notification. `onClosed` publishes the received code and reason
with `wasClean = true`, because OkHttp invokes it only after both peers complete the closing handshake.
`onFailure` publishes the exact Throwable; the core owns error and unclean-close event ordering.
`cancel()` targets the exact socket returned by the factory.

## Performance consequences

The common connection allocates one bridge and the OkHttp request/header objects required by OkHttp.
There is no second callback handle, executor task, Future, coroutine, atomic holder, or message queue.
Text frames remain Strings. Each outbound binary frame performs one unavoidable immutable ByteString
copy. Each inbound binary frame performs one unavoidable owned byte-array copy. Queue sampling is a
direct method call.

The adapter does not parse URLs, encode base64, stream through Java I/O wrappers, or retain live OkHttp
objects beyond the exact socket handle.

## Profile limits

- Custom handshake headers, cookies, authentication, proxies, TLS, compression, pings, and dispatcher
  configuration remain application/OkHttp policy.
- The core currently offers protocols only; it does not expose a general request-header surface.
- OkHttp's public API cannot send a close frame with no status payload, so the absent-code marker maps
  to 1000.
- Streaming binary messages and segmented Okio ownership transfer are separate profiles. The selected
  API buffers complete WebSocket messages.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- canonical URL and ordered protocol-header mapping;
- no protocol header for an empty offer;
- synchronous `onOpen` before factory return binds the exact socket;
- selected protocol and duplicate extension headers are snapshotted as Strings;
- text and binary callbacks map directly, with one incoming byte-array ownership copy;
- text and binary sends map directly, and binary sends use `ByteString.of(ByteBuffer)` rather than
  `BufferedBodySnapshot.copyBytes()`;
- queue size, absent/default close code, explicit close, and cancellation map to the exact socket;
- closing, clean closed, and failure callbacks retain their intended values;
- callbacks from another socket and non-101 opens are cancelled and reported;
- exactly one production bridge extends WebSocketListener and implements WebSocketTransportCall; and
- no generic queue, atomic holder, Future, coroutine, executor, Handler, java.net parser, growing
  stream, base64 layer, or Runnable enters the adapter.
