package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySource;
import io.github.akisarou.jvmwww.web.events.AbortSignal;
import io.github.akisarou.jvmwww.web.url.URL;
import java.util.Locale;
import java.util.Objects;

/** Buffered Request for the current direct-JVM Fetch profile. */
public final class Request {
    private final RuntimeInstance runtime;
    private final String url;
    private final String transportUrl;
    private final String method;
    private final Headers headers;
    private final BufferedBodySnapshot body;
    private final AbortSignal signal;

    public Request(RuntimeInstance runtime, String url) {
        this(runtime, new URL(runtime, Objects.requireNonNull(url, "url")));
    }

    public Request(RuntimeInstance runtime, URL url) {
        this(runtime, url, "GET", null, null, null, null);
    }

    public Request(
            RuntimeInstance runtime,
            String url,
            String method,
            Headers headers,
            byte[] body,
            AbortSignal signal) {
        this(
                runtime,
                new URL(runtime, Objects.requireNonNull(url, "url")),
                method,
                headers,
                body,
                null,
                signal);
    }

    public Request(
            RuntimeInstance runtime,
            URL url,
            String method,
            Headers headers,
            byte[] body,
            AbortSignal signal) {
        this(runtime, url, method, headers, body, null, signal);
    }

    /**
     * Creates a Request by capturing one immutable Blob, File, FormData, or custom body snapshot.
     *
     * <p>The named factory avoids a Java overload ambiguity between {@code byte[]} and
     * {@link BufferedBodySource} when a generated call passes a null body.</p>
     */
    public static Request withBody(
            RuntimeInstance runtime,
            String url,
            String method,
            Headers headers,
            BufferedBodySource body,
            AbortSignal signal) {
        return new Request(
                runtime,
                new URL(runtime, Objects.requireNonNull(url, "url")),
                method,
                headers,
                null,
                Objects.requireNonNull(body, "body"),
                signal);
    }

    /** URL-object variant of the buffered body snapshot constructor. */
    public static Request withBody(
            RuntimeInstance runtime,
            URL url,
            String method,
            Headers headers,
            BufferedBodySource body,
            AbortSignal signal) {
        return new Request(
                runtime,
                url,
                method,
                headers,
                null,
                Objects.requireNonNull(body, "body"),
                signal);
    }

    private Request(
            RuntimeInstance runtime,
            URL url,
            String method,
            Headers headers,
            byte[] byteBody,
            BufferedBodySource bodySource,
            AbortSignal signal) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        FetchRuntimeChecks.assertLanguageExecution(runtime);
        URL checkedUrl = Objects.requireNonNull(url, "url");
        if (checkedUrl.getRuntime() != runtime) {
            throw new IllegalArgumentException("Request URL belongs to another RuntimeInstance");
        }
        if (!checkedUrl.getUsername().isEmpty() || !checkedUrl.getPassword().isEmpty()) {
            throw new JsTypeError("Request URL cannot include credentials");
        }
        this.url = checkedUrl.getHref();
        this.transportUrl = excludeFragment(this.url);
        this.method = normalizeMethod(method == null ? "GET" : method);

        boolean hasBody = byteBody != null || bodySource != null;
        if (("GET".equals(this.method) || "HEAD".equals(this.method)) && hasBody) {
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

        BufferedBodySnapshot capturedBody = null;
        if (bodySource != null) {
            if (bodySource.getRuntime() != runtime) {
                throw new IllegalArgumentException("Request body belongs to another RuntimeInstance");
            }
            capturedBody = Objects.requireNonNull(
                    bodySource.snapshot(),
                    "BufferedBodySource.snapshot returned null");
        } else if (byteBody != null) {
            capturedBody = BufferedBodySnapshot.copyOf(byteBody, null);
        }
        if (capturedBody != null) {
            String inferredContentType = capturedBody.getContentType();
            if (inferredContentType != null
                    && !inferredContentType.isEmpty()
                    && !this.headers.has("content-type")) {
                this.headers.append("content-type", inferredContentType);
            }
        }
        this.body = capturedBody;
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

    String copyUrlForTransport() {
        assertAccess();
        return transportUrl;
    }

    BufferedBodySnapshot bodySnapshotForTransport() {
        assertAccess();
        return body;
    }

    private void assertAccess() {
        FetchRuntimeChecks.assertLanguageExecution(runtime);
    }

    private static String excludeFragment(String href) {
        int fragment = href.indexOf('#');
        return fragment < 0 ? href : href.substring(0, fragment);
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
