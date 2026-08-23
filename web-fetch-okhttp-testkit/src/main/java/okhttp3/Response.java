package okhttp3;

import java.io.Closeable;
import java.util.Objects;

/** Deterministic test-only immutable OkHttp response. */
public final class Response implements Closeable {
    private final Request request;
    private final int code;
    private final String message;
    private final Headers headers;
    private final ResponseBody body;
    private final Response priorResponse;
    private boolean closed;
    private Thread closeThread;

    private Response(
            Request request,
            int code,
            String message,
            Headers headers,
            ResponseBody body,
            Response priorResponse) {
        this.request = request;
        this.code = code;
        this.message = message;
        this.headers = headers;
        this.body = body;
        this.priorResponse = priorResponse;
    }

    public Request request() {
        return request;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public Headers headers() {
        return headers;
    }

    public ResponseBody body() {
        return body;
    }

    public Response priorResponse() {
        return priorResponse;
    }

    public boolean isClosed() {
        return closed;
    }

    public Thread getCloseThread() {
        return closeThread;
    }

    @Override
    public void close() {
        closeThread = Thread.currentThread();
        closed = true;
        if (body != null) {
            body.close();
        }
    }

    public static final class Builder {
        private Request request;
        private int code = 200;
        private String message = "OK";
        private final Headers.Builder headers = new Headers.Builder();
        private ResponseBody body;
        private Response priorResponse;

        public Builder request(Request value) {
            request = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder code(int value) {
            code = value;
            return this;
        }

        public Builder message(String value) {
            message = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder addHeader(String name, String value) {
            headers.add(name, value);
            return this;
        }

        public Builder body(ResponseBody value) {
            body = value;
            return this;
        }

        public Builder priorResponse(Response value) {
            priorResponse = value;
            return this;
        }

        public Response build() {
            return new Response(
                    Objects.requireNonNull(request, "request"),
                    code,
                    message,
                    headers.build(),
                    body,
                    priorResponse);
        }
    }
}
