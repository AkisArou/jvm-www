package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.url.URL;

/** Buffered network Response for the current Fetch profile. */
public final class Response {
    private final RuntimeInstance runtime;
    private final String url;
    private final int status;
    private final String statusText;
    private final Headers headers;
    private final BufferedBodySnapshot body;
    private final boolean redirected;
    private boolean bodyUsed;

    Response(RuntimeInstance runtime, FetchTransportResponse transport) {
        this.runtime = runtime;
        FetchRuntimeChecks.assertLanguageExecution(runtime);
        this.url = excludeFragment(new URL(runtime, transport.getUrl()).getHref());
        this.status = transport.getStatus();
        this.statusText = transport.getStatusText();
        this.headers =
                new Headers(
                        runtime,
                        transport.copyHeaderNames(),
                        transport.copyHeaderValues(),
                        true);
        this.body = BufferedBodySnapshot.fromOwnedBytes(transport.copyBody(), null);
        this.redirected = transport.isRedirected();
    }

    /** Internal clone path sharing immutable body bytes and copying immutable header metadata. */
    private Response(
            RuntimeInstance runtime,
            String url,
            int status,
            String statusText,
            Headers headers,
            BufferedBodySnapshot body,
            boolean redirected) {
        this.runtime = runtime;
        FetchRuntimeChecks.assertLanguageExecution(runtime);
        this.url = url;
        this.status = status;
        this.statusText = statusText;
        this.headers = headers;
        this.body = body;
        this.redirected = redirected;
    }

    public RuntimeInstance getRuntime() { return runtime; }
    public String getUrl() { assertAccess(); return url; }
    public int getStatus() { assertAccess(); return status; }
    public String getStatusText() { assertAccess(); return statusText; }
    public Headers getHeaders() { assertAccess(); return headers; }
    public boolean isRedirected() { assertAccess(); return redirected; }
    public boolean isOk() { assertAccess(); return status >= 200 && status <= 299; }
    public boolean isBodyUsed() { assertAccess(); return bodyUsed; }

    /** Returns a Promise fulfilled with an independent byte[] copy after one microtask hop. */
    public JsPromise arrayBuffer() {
        assertAccess();
        return startBodyRead(FetchBodyReadPromise.KIND_BYTES);
    }

    /** Selected-profile byte-array projection of Fetch Body.bytes(). */
    public JsPromise bytes() {
        assertAccess();
        return startBodyRead(FetchBodyReadPromise.KIND_BYTES);
    }

    /** Fetch text decoding is the exact UTF-8 replacement algorithm from web-encoding. */
    public JsPromise text() {
        assertAccess();
        return startBodyRead(FetchBodyReadPromise.KIND_TEXT);
    }

    /** Returns a Blob sharing the immutable response snapshot without another full byte copy. */
    public JsPromise blob() {
        assertAccess();
        return startBodyRead(FetchBodyReadPromise.KIND_BLOB);
    }

    /** Parses selected URL-encoded or bounded multipart bodies into an ordered FormData. */
    public JsPromise formData() {
        assertAccess();
        return startBodyRead(FetchBodyReadPromise.KIND_FORM_DATA);
    }

    /** Clones response metadata while sharing immutable buffered body bytes. */
    @Override
    public Response clone() {
        assertAccess();
        if (bodyUsed) {
            throw new JsTypeError("Cannot clone a Response whose body is already used");
        }
        return new Response(
                runtime,
                url,
                status,
                statusText,
                headers.immutableCopy(),
                body,
                redirected);
    }

    private JsPromise startBodyRead(int kind) {
        boolean unusable = bodyUsed;
        BufferedBodySnapshot captured = unusable ? null : body;
        if (!unusable) bodyUsed = true;
        return FetchBodyReadPromise.start(runtime, captured, kind, unusable, headers);
    }

    private void assertAccess() {
        FetchRuntimeChecks.assertLanguageExecution(runtime);
    }

    private static String excludeFragment(String href) {
        int fragment = href.indexOf('#');
        return fragment < 0 ? href : href.substring(0, fragment);
    }
}
