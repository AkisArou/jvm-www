package okhttp3;

import okio.ByteString;

public interface WebSocket {
    long queueSize();
    boolean send(String text);
    boolean send(ByteString bytes);
    boolean close(int code, String reason);
    void cancel();

    interface Factory {
        WebSocket newWebSocket(Request request, WebSocketListener listener);
    }
}
