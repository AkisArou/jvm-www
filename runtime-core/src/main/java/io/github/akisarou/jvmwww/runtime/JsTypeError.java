package io.github.akisarou.jvmwww.runtime;

/** Minimal Java representation of a JavaScript TypeError value used by the runtime core. */
public final class JsTypeError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JsTypeError(String message) {
        super(message);
    }
}
