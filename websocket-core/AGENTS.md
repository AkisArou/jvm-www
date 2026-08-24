# WebSocket core rules

- `WebSocket` owns browser-visible state and is confined to one `RuntimeInstance`; foreign callbacks only publish transport-safe data.
- One `WebSocket` object is its EventTarget, transport listener, handler-attribute listener, and reusable admitted `RuntimeTask`. Do not allocate a callback task or Runnable per message.
- Each open, message, peer-closing, or terminal notification is an intrusive event node and a distinct host task, preserving a complete microtask checkpoint between events.
- Event-handler replacement changes a callback field without removing and re-adding the fused listener, so listener-list position stays stable.
- Binary callback ownership transfers exactly once. Array-buffer delivery uses the owned byte array; Blob delivery wraps it through an immutable `BufferedBodySnapshot` without another full-body copy.
- Text sends use exact Web UTF-8 scalar length. Byte-array sends copy once only when transport submission is possible; closing/closed accounting must not copy discarded payloads.
- Blob sends flatten once to the immutable transport snapshot. Transports receive only strings, validated protocol arrays, and `BufferedBodySnapshot`, never owner-confined Blob or URL objects.
- `bufferedAmount` uses an allocation-free transport queued-byte sample plus one reusable owner sample event; never add one completion object per send.
- Do not add base64, generic event queues, futures, coroutines, executors, Android Handler scheduling, per-socket atomics, platform URL parsing, or a second scheduler.

Run `./scripts/test-websocket.sh` and the applicable core/event/body gates before every change.
