package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.web.bodies.Blob;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.bodies.FormDataParser;
import io.github.akisarou.jvmwww.web.encoding.TextDecoder;

/** One Body consumption Promise and its queued runtime microtask. */
final class FetchBodyReadPromise extends JsPromise implements RuntimeTask {
    static final int KIND_BYTES = 1;
    static final int KIND_TEXT = 2;
    static final int KIND_BLOB = 3;
    static final int KIND_FORM_DATA = 4;

    private BufferedBodySnapshot body;
    private final int kind;
    private final boolean unusable;
    private final String blobType;
    private final FetchMimeType.FormDataParameters formDataParameters;

    static JsPromise start(
            RuntimeInstance runtime,
            BufferedBodySnapshot body,
            int kind,
            boolean unusable,
            Headers headers) {
        String blobType = null;
        FetchMimeType.FormDataParameters formDataParameters = null;
        if (!unusable) {
            if (kind == KIND_BLOB) {
                blobType = FetchMimeType.extract(headers);
            } else if (kind == KIND_FORM_DATA) {
                formDataParameters = FetchMimeType.extractFormDataParameters(headers);
            }
        }
        FetchBodyReadPromise promise = new FetchBodyReadPromise(
                runtime,
                body,
                kind,
                unusable,
                blobType,
                formDataParameters);
        runtime.queueMicrotask(promise);
        return promise;
    }

    private FetchBodyReadPromise(
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
            rejectReference(new JsTypeError("Body is already used"));
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
                throw new AssertionError("Unknown Fetch body read kind: " + kind);
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
                    "Body formData() requires multipart/form-data or "
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
