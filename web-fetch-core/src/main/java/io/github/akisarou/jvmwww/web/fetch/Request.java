package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.events.AbortSignal;
import java.util.Locale;
import java.util.Objects;

/** Buffered Request for the current direct-JVM Fetch profile. */
public final class Request {
    private final RuntimeInstance runtime;
    private final String url;
    private final String method;
    private final Headers headers;
    private final byte[] body;
    private final AbortSignal signal;

    public Request(RuntimeInstance runtime, String url) {
        this(runtime, url, "GET", null, null, null);
    }

    public Request(
            RuntimeInstance runtime,
            String url,
            String method,
            Headers headers,
            byte[] body,
            AbortSignal signal) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        FetchRuntimeChecks.assertLanguageExecution(runtime);
        this.url = validateUrl(url);
        this.method = normalizeMethod(method == null ? "GET" : method);
        if (("GET".equals(this.method) || "HEAD".equals(this.method)) && body != null) {
            throw new JsTypeError("GET/HEAD request cannot have a body");
        }
        if (headers == null) {
            this.headers = new Headers(runtime);
        } else {
            if (headers.getRuntime() != runtime) {
                throw new IllegalArgumentException("Request headers belong to another RuntimeInstance");
            }
            this.headers = new Headers(headers);
        }
        if (signal != null && signal.getRuntime() != runtime) {
            throw new IllegalArgumentException("Request signal belongs to another RuntimeInstance");
        }
        this.body = body == null ? null : body.clone();
        this.signal = signal;
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public String getUrl() {
        assertAccess();
        return url;
    }

    public String getMethod() {
        assertAccess();
        return method;
    }

    public Headers getHeaders() {
        assertAccess();
        return headers;
    }

    public AbortSignal getSignal() {
        assertAccess();
        return signal;
    }

    byte[] copyBodyForTransport() {
        assertAccess();
        return body == null ? null : body.clone();
    }

    private void assertAccess() {
        FetchRuntimeChecks.assertLanguageExecution(runtime);
    }

    private static String validateUrl(String url) {
        String checked = Objects.requireNonNull(url, "url");
        if (!(checked.startsWith("http://") || checked.startsWith("https://"))) {
            throw new JsTypeError(
                    "Current Fetch profile requires an absolute http:// or https:// URL");
        }
        for (int i = 0; i < checked.length(); i++) {
            char c = checked.charAt(i);
            if (c <= 0x20 || c == 0x7f) {
                throw new JsTypeError("URL contains ASCII whitespace/control characters");
            }
        }
        return checked;
    }

    private static String normalizeMethod(String method) {
        String checked = Objects.requireNonNull(method, "method");
        if (checked.isEmpty()) {
            throw new JsTypeError("HTTP method must not be empty");
        }
        for (int i = 0; i < checked.length(); i++) {
            char c = checked.charAt(i);
            if (!isTokenChar(c)) {
                throw new JsTypeError("Invalid HTTP method");
            }
        }
        String upper = checked.toUpperCase(Locale.ROOT);
        if ("CONNECT".equals(upper) || "TRACE".equals(upper) || "TRACK".equals(upper)) {
            throw new JsTypeError("Forbidden HTTP method: " + upper);
        }
        if ("DELETE".equals(upper)
                || "GET".equals(upper)
                || "HEAD".equals(upper)
                || "OPTIONS".equals(upper)
                || "POST".equals(upper)
                || "PUT".equals(upper)) {
            return upper;
        }
        return checked;
    }

    private static boolean isTokenChar(char c) {
        if ((c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')) {
            return true;
        }
        switch (c) {
            case '!': case '#': case '$': case '%': case '&': case '\'': case '*': case '+':
            case '-': case '.': case '^': case '_': case '`': case '|': case '~':
                return true;
            default:
                return false;
        }
    }
}
