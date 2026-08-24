package io.github.akisarou.jvmwww.web.websocket;

import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;

/** Thread-safe transport handle retained by the WebSocket core. */
public interface WebSocketTransportCall {
    boolean sendText(String text);
    boolean sendBinary(BufferedBodySnapshot data);
    /** Queued application bytes, excluding framing and OS buffers. */
    long getQueuedByteCount();
    /** Code 0 means no status code was supplied. */
    boolean close(int code, String reason);
    void cancel();
}
