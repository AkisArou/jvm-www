package io.github.akisarou.jvmwww.web.websocket;

import io.github.akisarou.jvmwww.web.events.Event;
import io.github.akisarou.jvmwww.web.events.EventInit;

/** Intrusive event node: the delivered Event is also its ingress record. */
abstract class QueuedWebSocketEvent extends Event {
    QueuedWebSocketEvent nextWebSocketEvent;

    QueuedWebSocketEvent(String type) { super(type); }
    QueuedWebSocketEvent(String type, EventInit init) { super(type, init); }
    abstract void deliver(WebSocket socket);
    void discardPayload() {}
}
