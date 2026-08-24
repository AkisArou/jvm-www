package io.github.akisarou.jvmwww.web.websocket;

/** Foreign-thread-safe callback boundary implemented by WebSocket itself. */
public interface WebSocketTransportListener {
    void onOpen(String protocol, String extensions);
    void onTextMessage(String text);
    /** Ownership of bytes transfers to the WebSocket core. */
    void onBinaryMessage(byte[] bytes);
    void onClosing();
    void onClosed(int code, String reason, boolean wasClean);
    void onFailure(Throwable error);
}
