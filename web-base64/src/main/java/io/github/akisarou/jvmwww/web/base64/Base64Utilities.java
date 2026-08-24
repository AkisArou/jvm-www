package io.github.akisarou.jvmwww.web.base64;

import io.github.akisarou.jvmwww.web.events.DOMException;

/** Exact HTML atob/btoa algorithms over Java UTF-16 strings. */
public final class Base64Utilities {
    private static final String BTOA_ERROR_MESSAGE =
            "The string to be encoded contains characters outside of the Latin1 range.";
    private static final String ATOB_ERROR_MESSAGE =
            "The string to be decoded is not correctly encoded.";

    private Base64Utilities() {}

    /**
     * Applies HTML btoa to a DOMString represented by {@link String}.
     *
     * <p>Java {@code null} represents Web IDL's String(null), namely {@code "null"}. Dynamic
     * conversion of other language values remains a compiler/profile responsibility.</p>
     */
    public static String btoa(String data) {
        String input = data == null ? "null" : data;
        int inputLength = input.length();
        for (int i = 0; i < inputLength; i++) {
            if (input.charAt(i) > 0x00ff) {
                throw invalidCharacter(BTOA_ERROR_MESSAGE);
            }
        }
        if (inputLength == 0) {
            return "";
        }

        long outputLengthLong = (((long) inputLength + 2L) / 3L) * 4L;
        if (outputLengthLong > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Base64 output exceeds the Java String size limit");
        }
        char[] output = new char[(int) outputLengthLong];
        int inputIndex = 0;
        int outputIndex = 0;
        while (inputIndex + 2 < inputLength) {
            int first = input.charAt(inputIndex++);
            int second = input.charAt(inputIndex++);
            int third = input.charAt(inputIndex++);
            output[outputIndex++] = encodeSixBits(first >>> 2);
            output[outputIndex++] = encodeSixBits(((first & 0x03) << 4) | (second >>> 4));
            output[outputIndex++] = encodeSixBits(((second & 0x0f) << 2) | (third >>> 6));
            output[outputIndex++] = encodeSixBits(third & 0x3f);
        }

        int remaining = inputLength - inputIndex;
        if (remaining == 1) {
            int first = input.charAt(inputIndex);
            output[outputIndex++] = encodeSixBits(first >>> 2);
            output[outputIndex++] = encodeSixBits((first & 0x03) << 4);
            output[outputIndex++] = '=';
            output[outputIndex] = '=';
        } else if (remaining == 2) {
            int first = input.charAt(inputIndex++);
            int second = input.charAt(inputIndex);
            output[outputIndex++] = encodeSixBits(first >>> 2);
            output[outputIndex++] = encodeSixBits(((first & 0x03) << 4) | (second >>> 4));
            output[outputIndex++] = encodeSixBits((second & 0x0f) << 2);
            output[outputIndex] = '=';
        }
        return new String(output);
    }

    /**
     * Applies Infra's forgiving-base64 decode and returns HTML's binary ByteString result.
     *
     * <p>The implementation scans twice: once to determine exact padding/output length and once to
     * validate and decode directly into the sole temporary result character array.</p>
     */
    public static String atob(String data) {
        String input = data == null ? "null" : data;
        int cleanedLength = 0;
        char penultimate = 0;
        char last = 0;
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (isAsciiWhitespace(current)) {
                continue;
            }
            cleanedLength++;
            penultimate = last;
            last = current;
        }

        int paddingLength = 0;
        if ((cleanedLength & 3) == 0 && cleanedLength != 0 && last == '=') {
            paddingLength = 1;
            if (cleanedLength >= 2 && penultimate == '=') {
                paddingLength = 2;
            }
        }
        int dataLength = cleanedLength - paddingLength;
        int remainder = dataLength & 3;
        if (remainder == 1) {
            throw invalidCharacter(ATOB_ERROR_MESSAGE);
        }

        int outputLength = (dataLength >>> 2) * 3;
        if (remainder == 2) {
            outputLength++;
        } else if (remainder == 3) {
            outputLength += 2;
        }
        if (outputLength == 0) {
            // A whitespace-only input is valid, but still validate any logical padding below.
            if (dataLength == 0 && paddingLength == 0) {
                return "";
            }
        }

        char[] output = new char[outputLength];
        int outputIndex = 0;
        int dataSeen = 0;
        int paddingSeen = 0;
        int accumulator = 0;
        int sextetCount = 0;
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (isAsciiWhitespace(current)) {
                continue;
            }
            if (dataSeen < dataLength) {
                int decoded = decodeSixBits(current);
                if (decoded < 0) {
                    throw invalidCharacter(ATOB_ERROR_MESSAGE);
                }
                accumulator = (accumulator << 6) | decoded;
                dataSeen++;
                sextetCount++;
                if (sextetCount == 4) {
                    output[outputIndex++] = (char) ((accumulator >>> 16) & 0xff);
                    output[outputIndex++] = (char) ((accumulator >>> 8) & 0xff);
                    output[outputIndex++] = (char) (accumulator & 0xff);
                    accumulator = 0;
                    sextetCount = 0;
                }
            } else {
                if (current != '=') {
                    throw invalidCharacter(ATOB_ERROR_MESSAGE);
                }
                paddingSeen++;
            }
        }

        if (dataSeen != dataLength || paddingSeen != paddingLength) {
            throw invalidCharacter(ATOB_ERROR_MESSAGE);
        }
        if (sextetCount == 2) {
            output[outputIndex++] = (char) ((accumulator >>> 4) & 0xff);
        } else if (sextetCount == 3) {
            output[outputIndex++] = (char) ((accumulator >>> 10) & 0xff);
            output[outputIndex++] = (char) ((accumulator >>> 2) & 0xff);
        } else if (sextetCount != 0) {
            throw invalidCharacter(ATOB_ERROR_MESSAGE);
        }
        if (outputIndex != outputLength) {
            throw new AssertionError("Base64 output length mismatch");
        }
        return new String(output);
    }

    private static char encodeSixBits(int value) {
        if (value < 26) {
            return (char) ('A' + value);
        }
        if (value < 52) {
            return (char) ('a' + value - 26);
        }
        if (value < 62) {
            return (char) ('0' + value - 52);
        }
        return value == 62 ? '+' : '/';
    }

    private static int decodeSixBits(char value) {
        if (value >= 'A' && value <= 'Z') {
            return value - 'A';
        }
        if (value >= 'a' && value <= 'z') {
            return value - 'a' + 26;
        }
        if (value >= '0' && value <= '9') {
            return value - '0' + 52;
        }
        if (value == '+') {
            return 62;
        }
        if (value == '/') {
            return 63;
        }
        return -1;
    }

    private static boolean isAsciiWhitespace(char value) {
        return value == 0x0009
                || value == 0x000a
                || value == 0x000c
                || value == 0x000d
                || value == 0x0020;
    }

    private static DOMException invalidCharacter(String message) {
        return new DOMException(message, "InvalidCharacterError");
    }
}
