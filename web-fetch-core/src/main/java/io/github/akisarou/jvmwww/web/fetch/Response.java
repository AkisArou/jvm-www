package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.web.bodies.Blob;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.bodies.FormDataParser;
import io.github.akisarou.jvmwww.web.encoding.TextDecoder;
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
        return startBodyRead(BodyReadPromise.KIND_BYTES, null, null);
    }

    /** Selected-profile byte-array projection of Fetch Body.bytes(). */
    public JsPromise bytes() {
        assertAccess();
        return startBodyRead(BodyReadPromise.KIND_BYTES, null, null);
    }

    /** Fetch text decoding is the exact UTF-8 replacement algorithm from web-encoding. */
    public JsPromise text() {
        assertAccess();
        return startBodyRead(BodyReadPromise.KIND_TEXT, null, null);
    }

    /** Returns a Blob sharing the immutable response snapshot without another full byte copy. */
    public JsPromise blob() {
        assertAccess();
        return startBodyRead(
                BodyReadPromise.KIND_BLOB,
                FetchMimeType.extract(headers),
                null);
    }

    /** Parses selected URL-encoded or bounded multipart bodies into an ordered FormData. */
    public JsPromise formData() {
        assertAccess();
        return startBodyRead(
                BodyReadPromise.KIND_FORM_DATA,
                null,
                FetchMimeType.extractFormDataParameters(headers));
    }

    private JsPromise startBodyRead(
            int kind,
            String blobType,
            FetchMimeType.FormDataParameters formDataParameters) {
        BodyReadPromise promise;
        if (bodyUsed) {
            promise = new BodyReadPromise(
                    runtime,
                    null,
                    kind,
                    true,
                    blobType,
                    formDataParameters);
        } else {
            bodyUsed = true;
            promise = new BodyReadPromise(
                    runtime,
                    body,
                    kind,
                    false,
                    blobType,
                    formDataParameters);
        }
        runtime.queueMicrotask(promise);
        return promise;
    }

    private void assertAccess() {
        FetchRuntimeChecks.assertLanguageExecution(runtime);
    }

    private static String excludeFragment(String href) {
        int fragment = href.indexOf('#');
        return fragment < 0 ? href : href.substring(0, fragment);
    }

    private static final class BodyReadPromise extends JsPromise implements RuntimeTask {
        static final int KIND_BYTES = 1;
        static final int KIND_TEXT = 2;
        static final int KIND_BLOB = 3;
        static final int KIND_FORM_DATA = 4;

        private BufferedBodySnapshot body;
        private final int kind;
        private final boolean unusable;
        private final String blobType;
        private final FetchMimeType.FormDataParameters formDataParameters;

        BodyReadPromise(
                RuntimeInstance runtime,
                BufferedBodySnapshot body,
                int kind,
                boolean unusable,
                String blobType,
                FetchMimeType.FormDataParameters formDataParameters) {
            super(runtime);
            this.body = body;
            this.kind = kind;
            this.unusable = unusable;
            this.blobType = blobType;
            this.formDataParameters = formDataParameters;
        }

        @Override
        public void execute(RuntimeInstance runtime) {
            if (unusable) {
                rejectReference(new JsTypeError("Response body is already used"));
                return;
            }
            BufferedBodySnapshot captured = body;
            body = null;
            try {
                if (kind == KIND_BYTES) {
                    fulfillReference(captured.copyBytes());
                } else if (kind == KIND_TEXT) {
                    fulfillReference(new TextDecoder(runtime).decode(captured.copyBytes()));
                } else if (kind == KIND_BLOB) {
                    fulfillReference(Blob.fromSnapshot(runtime, captured, blobType));
                } else if (kind == KIND_FORM_DATA) {
                    fulfillReference(parseFormData(runtime, captured, formDataParameters));
                } else {
                    throw new AssertionError("Unknown response body read kind: " + kind);
                }
            } catch (Throwable error) {
                rethrowIfFatal(error);
                rejectReference(error);
            }
        }

        @Override
        public void discard() {
            body = null;
        }

        private static Object parseFormData(
                RuntimeInstance runtime,
                BufferedBodySnapshot body,
                FetchMimeType.FormDataParameters parameters) {
            if (parameters == null) {
                throw new JsTypeError(
                        "Response formData() requires multipart/form-data or "
                                + "application/x-www-form-urlencoded");
            }
            if (parameters.kind == FetchMimeType.FormDataParameters.URL_ENCODED) {
                return FormDataParser.parseUrlEncoded(runtime, body);
            }
            if (parameters.kind == FetchMimeType.FormDataParameters.MULTIPART) {
                return FormDataParser.parseMultipart(runtime, body, parameters.boundary);
            }
            throw new AssertionError("Unknown form data MIME kind: " + parameters.kind);
        }

        private static void rethrowIfFatal(Throwable error) {
            if (error instanceof ThreadDeath) throw (ThreadDeath) error;
            if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
            if (error instanceof LinkageError) throw (LinkageError) error;
        }
    }
}
