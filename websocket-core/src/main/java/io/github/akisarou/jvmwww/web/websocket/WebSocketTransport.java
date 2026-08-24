package io.github.akisarou.jvmwww.web.websocket;

/** Replaceable platform transport for one browser-shaped WebSocket connection. */
public interface WebSocketTransport {
    /** The listener may be called synchronously or from any thread. */
    WebSocketTransportCall start(WebSocketTransportRequest request, WebSocketTransportListener listener);
}
