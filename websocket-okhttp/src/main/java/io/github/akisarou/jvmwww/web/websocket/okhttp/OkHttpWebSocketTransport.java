package io.github.akisarou.jvmwww.web.websocket.okhttp;

import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransport;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportCall;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportListener;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportRequest;
import java.nio.ByteBuffer;
import java.util.Objects;
import okhttp3.Headers;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** WebSocket transport backed by an explicitly supplied OkHttp WebSocket factory. */
public final class OkHttpWebSocketTransport implements WebSocketTransport {
    private static final String PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    private static final String EXTENSIONS_HEADER = "Sec-WebSocket-Extensions";

    private final WebSocket.Factory factory;

    public OkHttpWebSocketTransport(WebSocket.Factory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public WebSocketTransportCall start(
            WebSocketTransportRequest request,
            WebSocketTransportListener listener) {
        WebSocketTransportRequest checkedRequest = Objects.requireNonNull(request, "request");
        WebSocketTransportListener checkedListener = Objects.requireNonNull(listener, "listener");

        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .url(checkedRequest.getUrl());
        if (checkedRequest.getProtocolCount() != 0) {
            requestBuilder.addHeader(PROTOCOL_HEADER, joinProtocols(checkedRequest));
        }

        OkHttpWebSocketCall bridge = new OkHttpWebSocketCall(checkedListener);
        WebSocket socket = Objects.requireNonNull(
                factory.newWebSocket(requestBuilder.build(), bridge),
                "WebSocket.Factory.newWebSocket returned null");
        bridge.bind(socket);
        return bridge;
    }

    private static String joinProtocols(WebSocketTransportRequest request) {
        int count = request.getProtocolCount();
        long capacity = (long) (count - 1) * 2L;
        for (int index = 0; index < count; index++) {
            capacity += request.getProtocol(index).length();
        }
        if (capacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("WebSocket protocol header exceeds Java String limits");
        }
        StringBuilder result = new StringBuilder((int) capacity);
        for (int index = 0; index < count; index++) {
            if (index != 0) result.append(", ");
            result.append(request.getProtocol(index));
        }
        return result.toString();
    }

    private static String combinedHeader(Headers headers, String expectedName) {
        String first = null;
        StringBuilder combined = null;
        for (int index = 0; index < headers.size(); index++) {
            if (!expectedName.equalsIgnoreCase(headers.name(index))) continue;
            String value = headers.value(index);
            if (first == null) {
                first = value;
            } else {
                if (combined == null) {
                    combined = new StringBuilder(first.length() + value.length() + 2);
                    combined.append(first);
                }
                combined.append(", ").append(value);
            }
        }
        if (combined != null) return combined.toString();
        return first == null ? "" : first;
    }

    /** One adapter object is both the OkHttp listener and the core transport handle. */
    private static final class OkHttpWebSocketCall extends WebSocketListener
            implements WebSocketTransportCall {
        private final WebSocketTransportListener listener;
        private volatile WebSocket socket;
        private boolean identityFailureReported;

        OkHttpWebSocketCall(WebSocketTransportListener listener) {
            this.listener = listener;
        }

        void bind(WebSocket startedSocket) {
            acceptSocket(Objects.requireNonNull(startedSocket, "startedSocket"));
        }

        @Override
        public boolean sendText(String text) {
            return requireSocket().send(Objects.requireNonNull(text, "text"));
        }

        @Override
        public boolean sendBinary(BufferedBodySnapshot data) {
            BufferedBodySnapshot checked = Objects.requireNonNull(data, "data");
            ByteBuffer bytes = checked.asReadOnlyByteBuffer();
            return requireSocket().send(ByteString.of(bytes));
        }

        @Override
        public long getQueuedByteCount() {
            return requireSocket().queueSize();
        }

        @Override
        public boolean close(int code, String reason) {
            int actualCode = code == 0 ? 1000 : code;
            return requireSocket().close(actualCode, Objects.requireNonNull(reason, "reason"));
        }

        @Override
        public void cancel() {
            WebSocket local = socket;
            if (local != null) local.cancel();
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            if (!acceptSocket(Objects.requireNonNull(webSocket, "webSocket"))) return;
            Response checkedResponse = Objects.requireNonNull(response, "response");
            if (checkedResponse.code() != 101) {
                failSocket(
                        webSocket,
                        new IllegalStateException(
                                "OkHttp WebSocket opened with HTTP status "
                                        + checkedResponse.code()));
                return;
            }
            Headers headers = checkedResponse.headers();
            listener.onOpen(
                    combinedHeader(headers, PROTOCOL_HEADER),
                    combinedHeader(headers, EXTENSIONS_HEADER));
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (!acceptSocket(Objects.requireNonNull(webSocket, "webSocket"))) return;
            listener.onTextMessage(Objects.requireNonNull(text, "text"));
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            if (!acceptSocket(Objects.requireNonNull(webSocket, "webSocket"))) return;
            listener.onBinaryMessage(Objects.requireNonNull(bytes, "bytes").toByteArray());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            if (!acceptSocket(Objects.requireNonNull(webSocket, "webSocket"))) return;
            listener.onClosing();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (!acceptSocket(Objects.requireNonNull(webSocket, "webSocket"))) return;
            listener.onClosed(code, Objects.requireNonNull(reason, "reason"), true);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable error, Response response) {
            if (!acceptSocket(Objects.requireNonNull(webSocket, "webSocket"))) return;
            listener.onFailure(Objects.requireNonNull(error, "error"));
        }

        private WebSocket requireSocket() {
            WebSocket local = socket;
            if (local == null) {
                throw new IllegalStateException("OkHttp WebSocket has not been bound yet");
            }
            return local;
        }

        private boolean acceptSocket(WebSocket candidate) {
            boolean reportFailure = false;
            synchronized (this) {
                if (socket == null) {
                    socket = candidate;
                    return true;
                }
                if (socket == candidate) return true;
                if (!identityFailureReported) {
                    identityFailureReported = true;
                    reportFailure = true;
                }
            }
            cancelQuietly(candidate);
            if (reportFailure) {
                listener.onFailure(new IllegalStateException(
                        "OkHttp callback delivered by another WebSocket"));
            }
            return false;
        }

        private void failSocket(WebSocket failedSocket, Throwable error) {
            cancelQuietly(failedSocket);
            listener.onFailure(error);
        }

        private static void cancelQuietly(WebSocket socket) {
            try {
                socket.cancel();
            } catch (Throwable error) {
                rethrowIfFatal(error);
            }
        }

        private static void rethrowIfFatal(Throwable error) {
            if (error instanceof ThreadDeath) throw (ThreadDeath) error;
            if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
            if (error instanceof LinkageError) throw (LinkageError) error;
        }
    }
}
