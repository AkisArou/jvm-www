# 0021 — Let each runtime own and cancel its active transports

Status: accepted for buffered Fetch and WebSocket transports.

## Context

The runtime already rejects foreign host-task admission after `RuntimeInstance.close()` and discards
payloads that had reached its ingress or microtask queues. That protects owner-confined language
state, but it does not cover a platform operation that is still active and has not published any
completion.

Two reached cases expose the gap:

- a Fetch call may remain blocked in DNS, connection setup, request upload, or response wait without
  invoking its callback; and
- a WebSocket may remain connecting, open, or waiting for a closing handshake without publishing an
  event.

Closing the runtime in either state previously left the application-owned transport working until
its own timeout or lifecycle policy stopped it. The eventual callback was safely discarded, but the
socket, dispatcher work, buffers, and radio/network activity were not deterministically released.

A generic `List<AutoCloseable>`, identity map, one registration object per operation, weak-reference
registry, or per-resource atomic token would fix lifetime tracking at a recurring allocation and
indirection cost. Registering only after a transport callback would still miss the exact no-callback
case. Making OkHttp clients runtime-owned would also be wrong: applications intentionally share and
configure those clients independently of one JavaScript runtime.

## Decision

Add a narrow `RuntimeOwnedResource` SPI to `runtime-core`:

```java
public interface RuntimeOwnedResource {
    void closeForRuntime();
}
```

`RuntimeInstance.registerOwnedResource(resource)` returns a non-negative integer slot during an
active owner turn. `unregisterOwnedResource(resource, slot)` may run on any thread and removes only
when both the slot and object identity still match. A stale slot can therefore never unregister a
new resource that reused it.

### Reusable slot representation

The runtime allocates one `RuntimeResourceRegistry` only when its first long-lived resource is
reached. The registry contains:

```text
RuntimeOwnedResource[] resources
int[] nextFreeSlot
int firstFreeSlot
int nextUnusedSlot
int activeCount
```

Registration either pops a free integer slot or appends at the high-water mark. Unregistration
pushes the slot back onto the primitive free list. Growth doubles both arrays with
`System.arraycopy`. The high-water mark is bounded by maximum concurrent resources rather than the
total number of operations over the runtime lifetime.

There is no registration node, boxed slot, map entry, weak reference, or per-operation atomic object.
FetchOperation and WebSocket store the returned primitive `int` inside their already-required fused
objects.

### Shutdown ordering

`RuntimeInstance.close()` remains owner-thread-only and remains illegal during an application host
turn or microtask checkpoint. It now performs these steps:

1. stop foreign admission;
2. atomically detach the registry's backing resource array without copying it;
3. enter a private owner cleanup turn;
4. call `closeForRuntime()` once for every still-registered resource;
5. leave the cleanup turn and mark the runtime closed;
6. close logical timers and discard admitted host tasks, microtasks, and rejection candidates; and
7. let an already-posted reusable wake observe the closed state and return.

The cleanup turn exists so an owner-confined resource can detach internal cancellation algorithms,
such as a Fetch operation's AbortSignal edge. It does not perform a microtask checkpoint. A cleanup
callback must not run generated TypeScript; any work accidentally queued during cleanup is discarded
with the rest of shutdown.

The registry is detached before invoking resources. A synchronous cancellation callback therefore
cannot re-enter or corrupt the active registry. New registration is rejected once admission stops.
Foreign unregistration racing detach either removes the exact resource first or loses harmlessly and
lets owner shutdown close it.

Non-fatal resource-close failures are reported through the existing `DISCARD` runtime error phase and
do not prevent later resources from closing. Fatal JVM errors are remembered until every detached
resource has received its cleanup callback, after which ordinary runtime shutdown state is complete
and the fatal error is rethrown.

### Fetch ownership

`FetchOperation` remains one object across:

```text
returned JsPromise
+ FetchTransportCallback
+ AbortAlgorithm
+ admitted RuntimeTask
+ RuntimeOwnedResource
```

It registers before AbortSignal attachment and before `FetchTransport.start()`, so synchronous
callbacks and no-callback transports are both covered. Normal owner delivery detaches the abort
algorithm and releases the slot before settling the Promise. Runtime shutdown detaches the abort
algorithm, marks the operation discarded, releases retained completion payloads, and cancels the
exact `FetchTransportCall` even when no completion was ever queued.

A completion claimed but not yet owner-delivered remains registered. This lets runtime shutdown
perform owner-confined AbortSignal cleanup and cancel the exact call before the admitted completion
is discarded. Once owner delivery finishes, later runtime shutdown does not cancel that completed
call.

### WebSocket ownership

`WebSocket` remains one object across:

```text
EventTarget
+ WebSocketTransportListener
+ event-handler listener
+ reusable admitted RuntimeTask
+ RuntimeOwnedResource
```

It registers immediately before transport start. Connecting, open, and closing-handshake sockets
remain runtime-owned. Runtime shutdown discards queued intrusive event payloads and cancels the exact
`WebSocketTransportCall`, even when no socket callback has occurred.

A terminal transport callback releases the resource slot from its callback thread before owner event
delivery. At that point the underlying transport has already closed or failed, so runtime shutdown
only needs to discard the queued close/error event; it must not cancel a completed transport again.
Wrong-protocol, failed-send, and owner-initiated terminal paths cancel first and release ownership
when they queue their terminal event.

## Performance consequences

The first active transport in a runtime allocates one registry object and two small arrays. Each Fetch
or WebSocket operation adds only one primitive slot field to an object that already exists. Register,
unregister, and free-slot reuse are O(1), apart from infrequent array growth. Runtime shutdown detaches
and scans the maximum-concurrency array once without copying it.

The common callback and event paths retain their existing fused objects. There is no new Runnable,
Future, coroutine, executor, Handler post, close wrapper, resolver, listener bridge, map, or weak
reference. Transport adapters remain application-owned and unchanged.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- released slots are reused without a registration object;
- exact foreign-thread unregistration wins cleanly against later runtime close;
- a resource close failure is reported and does not strand later resources;
- cleanup callbacks run on the owner inside the non-checkpointing cleanup turn;
- runtime close cancels a Fetch call that never published a completion;
- a claimed-but-undelivered Fetch completion remains owned until shutdown or owner delivery;
- an owner-delivered Fetch completion releases runtime ownership;
- runtime close cancels connecting, open, and unfinished-closing WebSockets;
- a terminal WebSocket callback releases ownership before owner event delivery;
- FetchOperation and WebSocket themselves implement `RuntimeOwnedResource` and store primitive slots;
- registry storage is one resource array plus one primitive free-list array; and
- no per-resource registration class, generic collection, atomic holder, weak reference, Runnable,
  future, coroutine, executor, or Android scheduler enters the ownership path.
