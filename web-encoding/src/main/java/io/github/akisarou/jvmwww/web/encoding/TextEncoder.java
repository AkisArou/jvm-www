package io.github.akisarou.jvmwww.web.encoding;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Objects;

/** Owner-confined WHATWG TextEncoder for UTF-8. */
public final class TextEncoder {
    private final RuntimeInstance runtime;

    public TextEncoder(RuntimeInstance runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        EncodingRuntimeChecks.assertLanguageExecution(runtime);
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public String getEncoding() {
        assertAccess();
        return "utf-8";
    }

    public byte[] encode() {
        return encode("");
    }

    /** Encodes a Java UTF-16 string after converting lone surrogates to U+FFFD. */
    public byte[] encode(String input) {
        assertAccess();
        String checked = Objects.requireNonNull(input, "input");
        byte[] result = new byte[Utf8.encodedLength(checked)];
        long progress = Utf8.encodeInto(checked, result);
        if ((int) progress != result.length || (int) (progress >>> 32) != checked.length()) {
            throw new AssertionError("Exact UTF-8 output length was not filled");
        }
        return result;
    }

    /** Encodes complete scalar values only; no UTF-8 sequence is partially written. */
    public TextEncoderEncodeIntoResult encodeInto(String source, byte[] destination) {
        assertAccess();
        String checkedSource = Objects.requireNonNull(source, "source");
        byte[] checkedDestination = Objects.requireNonNull(destination, "destination");
        long progress = Utf8.encodeInto(checkedSource, checkedDestination);
        return new TextEncoderEncodeIntoResult(progress >>> 32, progress & 0xffffffffL);
    }

    private void assertAccess() {
        EncodingRuntimeChecks.assertLanguageExecution(runtime);
    }
}
