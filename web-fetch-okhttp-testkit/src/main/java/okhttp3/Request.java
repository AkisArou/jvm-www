package okhttp3;

import java.util.Objects;

/** Deterministic test-only immutable OkHttp request. */
public final class Request {
    private final HttpUrl url;
    private final String method;
    private final Headers headers;
    private final RequestBody body;

    private Request(HttpUrl url, String method, Headers headers, RequestBody body) {
        this.url = url;
        this.method = method;
        this.headers = headers;
        this.body = body;
    }

    public HttpUrl url() {
        return url;
    }

    public String method() {
        return method;
    }

    public Headers headers() {
        return headers;
    }

    public RequestBody body() {
        return body;
    }

    public static final class Builder {
        private HttpUrl url;
        private String method = "GET";
        private RequestBody body;
        private final Headers.Builder headers = new Headers.Builder();

        public Builder url(String value) {
            url = new HttpUrl(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder method(String value, RequestBody requestBody) {
            String checked = Objects.requireNonNull(value, "value");
            if (("GET".equals(checked) || "HEAD".equals(checked)) && requestBody != null) {
                throw new IllegalArgumentException("method " + checked + " must not have a request body");
            }
            if (requiresRequestBody(checked) && requestBody == null) {
                throw new IllegalArgumentException("method " + checked + " must have a request body");
            }
            method = checked;
            body = requestBody;
            return this;
        }

        public Builder addHeader(String name, String value) {
            headers.add(name, value);
            return this;
        }

        public Request build() {
            return new Request(
                    Objects.requireNonNull(url, "url"),
                    method,
                    headers.build(),
                    body);
        }

        private static boolean requiresRequestBody(String value) {
            return "POST".equals(value)
                    || "PUT".equals(value)
                    || "PATCH".equals(value)
                    || "PROPPATCH".equals(value)
                    || "QUERY".equals(value)
                    || "REPORT".equals(value);
        }
    }
}
