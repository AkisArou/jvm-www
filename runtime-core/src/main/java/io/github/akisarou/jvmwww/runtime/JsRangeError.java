package io.github.akisarou.jvmwww.runtime;

/** Minimal Java representation of a JavaScript RangeError value used by capability modules. */
public final class JsRangeError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JsRangeError(String message) {
        super(message);
    }
}
