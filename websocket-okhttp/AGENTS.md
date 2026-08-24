# OkHttp WebSocket transport rules

- `websocket-okhttp` is replaceable transport plumbing. It accepts an application-owned
  `okhttp3.WebSocket.Factory` and owns no runtime scheduling, EventTarget state, protocol policy,
  TLS policy, proxy policy, ping policy, dispatcher, or client lifecycle.
- One bridge object extends `okhttp3.WebSocketListener` and implements `WebSocketTransportCall`.
  Do not add another listener, cancellation handle, callback Runnable, Future, or coroutine.
- Callback identity is exact. A callback from another OkHttp WebSocket is cancelled and reported as
  a transport failure; it is never delivered as an application message.
- Snapshot the selected protocol and extensions as Strings on the OkHttp callback thread. Never
  retain or publish an OkHttp `Response`, `Headers`, `WebSocket`, or Okio `ByteString` to the core.
- Incoming binary frames perform the required single `ByteString.toByteArray()` ownership copy and
  transfer that array to the core. Do not base64-encode or copy it again.
- Outgoing binary frames convert the immutable snapshot through its read-only `ByteBuffer` view and
  `ByteString.of(ByteBuffer)`. Never call `BufferedBodySnapshot.copyBytes()` in this adapter.
- `getQueuedByteCount()` maps directly to `WebSocket.queueSize()`; never add a polling timer or queue
  observer. `cancel()` always targets the exact socket returned by the supplied factory.
- OkHttp requires a close status code, so the core's absent-code marker `0` maps to normal closure
  code `1000`. Explicit validated codes and reasons pass through unchanged.
- Do not add java.net URL parsing, streams, generic message queues, atomics, executors, Android
  Handlers, or any second scheduler.

Run `./scripts/test-websocket.sh` and `./scripts/test-websocket-okhttp.sh` before every change.
