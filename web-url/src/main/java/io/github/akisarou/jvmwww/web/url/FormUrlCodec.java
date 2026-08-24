package io.github.akisarou.jvmwww.web.url;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.encoding.TextDecoder;
import io.github.akisarou.jvmwww.web.encoding.TextEncoder;
import io.github.akisarou.jvmwww.web.encoding.Utf8Codec;
import java.util.ArrayList;

/** application/x-www-form-urlencoded parser and serializer used by URLSearchParams. */
final class FormUrlCodec {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final byte[] EMPTY_BYTES = new byte[0];

    private FormUrlCodec() {}

    static void parse(
            RuntimeInstance runtime,
            String input,
            ArrayList<URLSearchParams.Entry> output) {
        String source = input;
        if (!source.isEmpty() && source.charAt(0) == '?') {
            source = source.substring(1);
        }
        if (source.isEmpty()) return;

        TextEncoder encoder = new TextEncoder(runtime);
        TextDecoder decoder = new TextDecoder(runtime, "utf-8", false, true);
        byte[] bytes = encoder.encode(source);
        int sequenceStart = 0;
        for (int index = 0; index <= bytes.length; index++) {
            if (index != bytes.length && bytes[index] != '&') continue;
            if (index != sequenceStart) {
                parseSequence(bytes, sequenceStart, index, decoder, output);
            }
            sequenceStart = index + 1;
        }
    }

    static String serialize(
            RuntimeInstance runtime,
            ArrayList<URLSearchParams.Entry> entries) {
        if (entries.isEmpty()) return "";
        TextEncoder encoder = new TextEncoder(runtime);
        StringBuilder result = new StringBuilder(entries.size() * 8);
        for (int index = 0; index < entries.size(); index++) {
            if (index != 0) result.append('&');
            URLSearchParams.Entry entry = entries.get(index);
            appendEncoded(result, encoder.encode(entry.name));
            result.append('=');
            appendEncoded(result, encoder.encode(entry.value));
        }
        return result.toString();
    }

    /** Writes one exact ASCII form payload without an intermediate serialized String. */
    static byte[] serializeBytes(ArrayList<URLSearchParams.Entry> entries) {
        if (entries.isEmpty()) return EMPTY_BYTES;

        int maximumUtf8Length = 0;
        for (int index = 0; index < entries.size(); index++) {
            URLSearchParams.Entry entry = entries.get(index);
            maximumUtf8Length = Math.max(
                    maximumUtf8Length,
                    Utf8Codec.encodedLength(entry.name));
            maximumUtf8Length = Math.max(
                    maximumUtf8Length,
                    Utf8Codec.encodedLength(entry.value));
        }

        byte[] scratch = maximumUtf8Length == 0
                ? EMPTY_BYTES
                : new byte[maximumUtf8Length];
        long outputLength = entries.size() * 2L - 1L;
        for (int index = 0; index < entries.size(); index++) {
            URLSearchParams.Entry entry = entries.get(index);
            outputLength += encodedComponentLength(entry.name, scratch);
            outputLength += encodedComponentLength(entry.value, scratch);
            if (outputLength > Integer.MAX_VALUE) {
                throw new OutOfMemoryError(
                        "URLSearchParams encoded body exceeds Java array limits");
            }
        }

        byte[] output = new byte[(int) outputLength];
        int written = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (index != 0) output[written++] = '&';
            URLSearchParams.Entry entry = entries.get(index);
            written = writeEncodedComponent(entry.name, scratch, output, written);
            output[written++] = '=';
            written = writeEncodedComponent(entry.value, scratch, output, written);
        }
        if (written != output.length) {
            throw new AssertionError("Exact form body length was not filled");
        }
        return output;
    }

    private static void parseSequence(
            byte[] input,
            int start,
            int end,
            TextDecoder decoder,
            ArrayList<URLSearchParams.Entry> output) {
        int equals = -1;
        for (int index = start; index < end; index++) {
            if (input[index] == '=') {
                equals = index;
                break;
            }
        }
        int nameEnd = equals < 0 ? end : equals;
        int valueStart = equals < 0 ? end : equals + 1;
        String name = decoder.decode(percentDecode(input, start, nameEnd));
        String value = decoder.decode(percentDecode(input, valueStart, end));
        output.add(new URLSearchParams.Entry(name, value));
    }

    private static byte[] percentDecode(byte[] input, int start, int end) {
        byte[] output = new byte[end - start];
        int written = 0;
        for (int index = start; index < end; index++) {
            int current = input[index] & 0xff;
            if (current == '+') {
                output[written++] = 0x20;
            } else if (current == '%' && index + 2 < end) {
                int high = hexValue(input[index + 1] & 0xff);
                int low = hexValue(input[index + 2] & 0xff);
                if (high >= 0 && low >= 0) {
                    output[written++] = (byte) ((high << 4) | low);
                    index += 2;
                } else {
                    output[written++] = input[index];
                }
            } else {
                output[written++] = input[index];
            }
        }
        if (written == output.length) return output;
        byte[] exact = new byte[written];
        System.arraycopy(output, 0, exact, 0, written);
        return exact;
    }

    private static long encodedComponentLength(String value, byte[] scratch) {
        int byteLength = encodeIntoScratch(value, scratch);
        long length = 0L;
        for (int index = 0; index < byteLength; index++) {
            int current = scratch[index] & 0xff;
            length += isFormSafe(current) || current == 0x20 ? 1L : 3L;
        }
        return length;
    }

    private static int writeEncodedComponent(
            String value,
            byte[] scratch,
            byte[] output,
            int offset) {
        int byteLength = encodeIntoScratch(value, scratch);
        int written = offset;
        for (int index = 0; index < byteLength; index++) {
            int current = scratch[index] & 0xff;
            if (isFormSafe(current)) {
                output[written++] = (byte) current;
            } else if (current == 0x20) {
                output[written++] = '+';
            } else {
                output[written++] = '%';
                output[written++] = (byte) HEX[current >>> 4];
                output[written++] = (byte) HEX[current & 0x0f];
            }
        }
        return written;
    }

    private static int encodeIntoScratch(String value, byte[] scratch) {
        long progress = Utf8Codec.encodeInto(value, scratch, 0);
        if ((int) (progress >>> 32) != value.length()) {
            throw new AssertionError("UTF-8 scratch buffer was undersized");
        }
        return (int) progress;
    }

    private static void appendEncoded(StringBuilder output, byte[] bytes) {
        for (byte value : bytes) {
            int current = value & 0xff;
            if (isFormSafe(current)) {
                output.append((char) current);
            } else if (current == 0x20) {
                output.append('+');
            } else {
                output.append('%')
                        .append(HEX[current >>> 4])
                        .append(HEX[current & 0x0f]);
            }
        }
    }

    private static boolean isFormSafe(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '*'
                || value == '-'
                || value == '.'
                || value == '_';
    }

    private static int hexValue(int value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        return -1;
    }
}
