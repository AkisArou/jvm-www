package io.github.akisarou.jvmwww.web.websocket;

import io.github.akisarou.jvmwww.web.bodies.Blob;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import java.util.Objects;

/** WebSocket message event for the selected text/Blob/byte-array profile. */
public final class MessageEvent extends QueuedWebSocketEvent {
    private static final Object[] EMPTY_PORTS = new Object[0];
    private Object data;
    private byte[] binaryData;
    private final String origin;

    public MessageEvent(String type, Object data, String origin) {
        super(Objects.requireNonNull(type, "type"));
        this.data = data;
        this.origin = WebSocketScalar.fromString(origin == null ? "" : origin, "origin");
    }

    static MessageEvent text(String text, String origin) {
        return new MessageEvent("message", WebSocketScalar.fromString(text, "text"), origin);
    }

    static MessageEvent binary(byte[] bytes, String origin) {
        MessageEvent event = new MessageEvent("message", null, origin);
        event.binaryData = Objects.requireNonNull(bytes, "bytes");
        return event;
    }

    public Object getData() { return data; }
    public String getOrigin() { return origin; }
    public String getLastEventId() { return ""; }
    public Object getSource() { return null; }
    public Object[] getPorts() { return EMPTY_PORTS; }

    @Override
    void deliver(WebSocket socket) {
        if (binaryData != null) {
            byte[] owned = binaryData;
            binaryData = null;
            if (WebSocket.BINARY_TYPE_ARRAYBUFFER.equals(socket.getBinaryTypeInternal())) {
                data = owned;
            } else {
                data = Blob.fromSnapshot(
                        socket.getRuntime(),
                        BufferedBodySnapshot.fromOwnedBytes(owned, null),
                        "");
            }
        }
        socket.deliverMessage(this);
    }

    @Override
    void discardPayload() {
        binaryData = null;
        data = null;
    }
}
