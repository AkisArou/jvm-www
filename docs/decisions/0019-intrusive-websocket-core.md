# 0019 — Fuse WebSocket ingress with one reusable owner task

Status: accepted for the first transport-independent `websocket-core` slice.

## Context

The runtime already has owner-confined events, exact UTF-8 scalar conversion, canonical special URLs, immutable Blob snapshots, and a coalesced foreign host-task queue. WebSocket combines those boundaries: callbacks can arrive concurrently, every message is a task, binary ownership crosses threads, and observable state remains owner-confined.

Draining many messages in one capability callback would be faster but observably wrong because it would omit the complete microtask checkpoint between WebSocket tasks. A callback Runnable, Future, resolver, generic queue node, or base64 wrapper per message would also add avoidable allocation or copies.

## Decision

Add `websocket-core` with `WebSocket`, `MessageEvent`, `CloseEvent`, and a minimal replaceable transport SPI. `WebSocket` is simultaneously its EventTarget, the foreign transport listener, the handler-attribute EventListener, and one reusable admitted RuntimeTask. Incoming event objects are intrusive queue nodes; the socket task removes exactly one node and re-admits itself only if another remains.

String `ws:`/`wss:` inputs are canonicalized through the existing special URL implementation by mapping only the parser scheme and serializing back to WebSocket schemes. The Java URL overload is a profile convenience that maps canonical http/https URL values to ws/wss. Fragments, credentials, malformed protocols, and duplicate subprotocols fail synchronously.

Transport callbacks transfer only Java-owned strings or byte arrays. Binary callback ownership transfers to the core. At owner delivery, `binaryType=arraybuffer` exposes the owned byte array directly; `binaryType=blob` moves it through `BufferedBodySnapshot.fromOwnedBytes` and `Blob.fromSnapshot`, avoiding another full payload copy.

Outbound byte-array sends make one defensive ownership copy only on the live transport path. Blob sends flatten once to an immutable transport snapshot. Text sends calculate exact UTF-8 application bytes without allocating encoded output.

`bufferedAmount` uses the transport's thread-safe queued-application-byte sample. Successful sends synchronously increment the owner cache and schedule at most one reusable buffer-sample event; no per-write completion callback is required.

Close code/reason validation is synchronous. Transport failure dispatches `error` followed by an abnormal `close` in the same terminal host task. First terminal publication wins. Peer closing is a queued state transition and suppresses later payload publication.

## Performance consequences

There is no capability-specific Runnable, Future, coroutine, executor, Android Handler, generic event queue, per-socket atomic holder, Base64 layer, ByteBuffer conversion, or `java.net` URL parser. Message/close events are their own queue nodes, and every message remains a separate runtime host task.

## Profile limits

This slice deliberately excludes the OkHttp adapter, custom headers/cookie policy, ping/pong APIs, reconnect behavior, streaming frames, and runtime-wide persistent socket registration. Those require separate transport and lifecycle decisions.

## Required evidence

Permanent Java 8 tests and structural gates cover canonical URLs and protocols, synchronous callback deferral, stable event handlers, message/microtask ordering, binary ownership, send copies, buffered amount, peer closing, close validation, terminal ordering, owner confinement, and the absence of generic queues, schedulers, base64, charset shortcuts, `java.net`, and callback Runnable jobs.
