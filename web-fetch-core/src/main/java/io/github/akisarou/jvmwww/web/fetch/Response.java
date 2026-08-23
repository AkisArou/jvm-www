package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.web.encoding.TextDecoder;

/** Buffered network Response for the current Fetch profile. */
public final class Response {
    private final RuntimeInstance runtime;
    private final String url;
    private final int status;
    private final String statusText;
    private final Headers headers;
    private final byte[] body;
    private final boolean redirected;
    private boolean bodyUsed;

    Response(RuntimeInstance runtime, FetchTransportResponse transport) {
        this.runtime = runtime;
        FetchRuntimeChecks.assertLanguageExecution(runtime);
        this.url = transport.getUrl();
        this.status = transport.getStatus();
        this.statusText = transport.getStatusText();
        this.headers =
                new Headers(
                        runtime,
                        transport.copyHeaderNames(),
                        transport.copyHeaderValues(),
                        true);
        this.body = transport.copyBody();
        this.redirected = transport.isRedirected();
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
        return startBodyRead(BodyReadPromise.KIND_BYTES);
    }

    /** Returns a Promise fulfilled through the selected WHATWG UTF-8 decoder. */
    public JsPromise text() {
        assertAccess();
        return startBodyRead(BodyReadPromise.KIND_TEXT);
    }

    private JsPromise startBodyRead(int kind) {
        BodyReadPromise promise;
        if (bodyUsed) {
            promise = new BodyReadPromise(runtime, null, kind, true);
        } else {
            bodyUsed = true;
            promise = new BodyReadPromise(runtime, body, kind, false);
        }
        runtime.queueMicrotask(promise);
        return promise;
    }

    private void assertAccess() {
        FetchRuntimeChecks.assertLanguageExecution(runtime);
    }

    private static final class BodyReadPromise extends JsPromise implements RuntimeTask {
        static final int KIND_BYTES = 1;
        static final int KIND_TEXT = 2;

        private byte[] bytes;
        private final int kind;
        private final boolean unusable;

        BodyReadPromise(RuntimeInstance runtime, byte[] bytes, int kind, boolean unusable) {
            super(runtime);
            this.bytes = bytes;
            this.kind = kind;
            this.unusable = unusable;
        }

        @Override
        public void execute(RuntimeInstance runtime) {
            if (unusable) {
                rejectReference(new JsTypeError("Response body is already used"));
                return;
            }
            byte[] captured = bytes;
            bytes = null;
            if (kind == KIND_BYTES) {
                fulfillReference(captured.clone());
            } else if (kind == KIND_TEXT) {
                fulfillReference(new TextDecoder(runtime).decode(captured));
            } else {
                throw new AssertionError("Unknown response body read kind: " + kind);
            }
        }

        @Override
        public void discard() {
            bytes = null;
        }
    }
}
