package okhttp3;

public final class Response {
    private final int code;
    private final Headers headers;

    public Response(int code, Headers headers) {
        this.code = code;
        this.headers = headers;
    }

    public int code() { return code; }
    public Headers headers() { return headers; }
}
