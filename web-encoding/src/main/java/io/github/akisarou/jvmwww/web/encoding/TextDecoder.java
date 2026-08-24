package io.github.akisarou.jvmwww.web.encoding;

import io.github.akisarou.jvmwww.runtime.JsRangeError;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Objects;

/** Owner-confined WHATWG TextDecoder for the selected UTF-8 profile. */
public final class TextDecoder {
    private static final int DEFAULT_LOWER_BOUNDARY = 0x80;
    private static final int DEFAULT_UPPER_BOUNDARY = 0xbf;

    private final RuntimeInstance runtime;
    private final boolean fatal;
    private final boolean ignoreBOM;

    private boolean doNotFlush;
    private boolean bomSeen;
    private int codePoint;
    private int bytesSeen;
    private int bytesNeeded;
    private int lowerBoundary = DEFAULT_LOWER_BOUNDARY;
    private int upperBoundary = DEFAULT_UPPER_BOUNDARY;

    public TextDecoder(RuntimeInstance runtime) {
        this(runtime, "utf-8", TextDecoderOptions.DEFAULT);
    }

    public TextDecoder(RuntimeInstance runtime, String label) {
        this(runtime, label, TextDecoderOptions.DEFAULT);
    }

    /** Static-profile constructor path that avoids an options object at generated call sites. */
    public TextDecoder(
            RuntimeInstance runtime,
            String label,
            boolean fatal,
            boolean ignoreBOM) {
        this(runtime, label, new TextDecoderOptions(fatal, ignoreBOM));
    }

    public TextDecoder(
            RuntimeInstance runtime,
            String label,
            TextDecoderOptions options) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        EncodingRuntimeChecks.assertLanguageExecution(runtime);
        requireUtf8Label(Objects.requireNonNull(label, "label"));
        TextDecoderOptions checked =
                options == null ? TextDecoderOptions.DEFAULT : options;
        this.fatal = checked.isFatal();
        this.ignoreBOM = checked.isIgnoreBOM();
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public String getEncoding() {
        assertAccess();
        return "utf-8";
    }

    public boolean isFatal() {
        assertAccess();
        return fatal;
    }

    public boolean isIgnoreBOM() {
        assertAccess();
        return ignoreBOM;
    }

    /** Flushes any streaming decoder state with no additional input. */
    public String decode() {
        assertAccess();
        return decodeInternal(null, 0, 0, false);
    }

    public String decode(byte[] input) {
        return decode(input, TextDecodeOptions.DEFAULT);
    }

    /** Decodes one immutable byte-array range without allocating a temporary slice. */
    public String decode(byte[] input, int offset, int length) {
        assertAccess();
        byte[] checked = Objects.requireNonNull(input, "input");
        checkRange(checked.length, offset, length);
        return decodeInternal(checked, offset, length, false);
    }

    /** Static-profile call path that avoids allocating a TextDecodeOptions object. */
    public String decode(byte[] input, boolean stream) {
        assertAccess();
        byte[] checked = Objects.requireNonNull(input, "input");
        return decodeInternal(checked, 0, checked.length, stream);
    }

    public String decode(byte[] input, TextDecodeOptions options) {
        assertAccess();
        byte[] checkedInput = Objects.requireNonNull(input, "input");
        TextDecodeOptions checked = options == null ? TextDecodeOptions.DEFAULT : options;
        return decodeInternal(checkedInput, 0, checkedInput.length, checked.isStream());
    }

    private String decodeInternal(byte[] input, int offset, int length, boolean stream) {
        if (!doNotFlush) {
            resetSequence();
            bomSeen = false;
        }
        doNotFlush = stream;

        StringBuilder output = new StringBuilder(length);
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            int current = input[index] & 0xff;
            boolean reprocess;
            do {
                reprocess = false;
                if (bytesNeeded == 0) {
                    if (current <= 0x7f) {
                        appendCodePoint(output, current);
                    } else if (current >= 0xc2 && current <= 0xdf) {
                        bytesNeeded = 1;
                        codePoint = current & 0x1f;
                    } else if (current >= 0xe0 && current <= 0xef) {
                        if (current == 0xe0) lowerBoundary = 0xa0;
                        if (current == 0xed) upperBoundary = 0x9f;
                        bytesNeeded = 2;
                        codePoint = current & 0x0f;
                    } else if (current >= 0xf0 && current <= 0xf4) {
                        if (current == 0xf0) lowerBoundary = 0x90;
                        if (current == 0xf4) upperBoundary = 0x8f;
                        bytesNeeded = 3;
                        codePoint = current & 0x07;
                    } else {
                        decodingError(output);
                    }
                } else if (current < lowerBoundary || current > upperBoundary) {
                    resetSequence();
                    decodingError(output);
                    reprocess = true;
                } else {
                    lowerBoundary = DEFAULT_LOWER_BOUNDARY;
                    upperBoundary = DEFAULT_UPPER_BOUNDARY;
                    codePoint = (codePoint << 6) | (current & 0x3f);
                    bytesSeen++;
                    if (bytesSeen == bytesNeeded) {
                        int completed = codePoint;
                        resetSequence();
                        appendCodePoint(output, completed);
                    }
                }
            } while (reprocess);
        }

        if (!stream && bytesNeeded != 0) {
            resetSequence();
            decodingError(output);
        }
        return output.toString();
    }

    private void decodingError(StringBuilder output) {
        if (fatal) {
            throw new JsTypeError("The encoded data was not valid UTF-8");
        }
        appendCodePoint(output, 0xfffd);
    }

    private void appendCodePoint(StringBuilder output, int value) {
        if (!ignoreBOM && !bomSeen) {
            bomSeen = true;
            if (value == 0xfeff) {
                return;
            }
        }
        output.appendCodePoint(value);
    }

    private void resetSequence() {
        codePoint = 0;
        bytesSeen = 0;
        bytesNeeded = 0;
        lowerBoundary = DEFAULT_LOWER_BOUNDARY;
        upperBoundary = DEFAULT_UPPER_BOUNDARY;
    }

    private void assertAccess() {
        EncodingRuntimeChecks.assertLanguageExecution(runtime);
    }

    private static void checkRange(int arrayLength, int offset, int length) {
        if (offset < 0 || length < 0 || offset > arrayLength - length) {
            throw new IndexOutOfBoundsException(
                    "UTF-8 input range does not fit array: offset="
                            + offset + ", length=" + length);
        }
    }

    private static void requireUtf8Label(String label) {
        int start = 0;
        int end = label.length();
        while (start < end && isAsciiWhitespace(label.charAt(start))) start++;
        while (end > start && isAsciiWhitespace(label.charAt(end - 1))) end--;
        if (asciiEquals(label, start, end, "utf-8")
                || asciiEquals(label, start, end, "utf8")
                || asciiEquals(label, start, end, "unicode-1-1-utf-8")) {
            return;
        }
        throw new JsRangeError(
                "Current TextDecoder profile supports UTF-8 labels only: " + label);
    }

    private static boolean asciiEquals(String value, int start, int end, String expected) {
        if (end - start != expected.length()) return false;
        for (int index = 0; index < expected.length(); index++) {
            char actual = value.charAt(start + index);
            char wanted = expected.charAt(index);
            if (actual >= 'A' && actual <= 'Z') actual = (char) (actual + ('a' - 'A'));
            if (actual != wanted) return false;
        }
        return true;
    }

    private static boolean isAsciiWhitespace(char value) {
        return value == '\t'
                || value == '\n'
                || value == '\f'
                || value == '\r'
                || value == ' ';
    }
}
