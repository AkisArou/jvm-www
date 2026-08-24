package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.JsRangeError;
import io.github.akisarou.jvmwww.runtime.JsTypeError;

/** Two-pass exact-size multipart/form-data serializer. */
final class MultipartEncoder {
    private static final String DISPOSITION = "Content-Disposition: form-data; name=\"";
    private static final String FILENAME = "\"; filename=\"";
    private static final String CONTENT_TYPE = "Content-Type: ";
    private static final String OCTET_STREAM = "application/octet-stream";

    private MultipartEncoder() {}

    static BufferedBodySnapshot encode(FormData formData, String boundary) {
        validateBoundary(boundary);
        int entryCount = formData.size();
        long total = closingLength(boundary);
        for (int index = 0; index < entryCount; index++) {
            total = add(total, partPrefixLength(boundary));
            total = add(total, DISPOSITION.length());
            total = add(total, normalizedEscapedLength(formData.nameAt(index)));
            if (formData.kindAt(index) == FormData.stringKind()) {
                total = add(total, 3L);
                total = add(total, 2L);
                total = add(total, normalizedUtf8Length((String) formData.valueAt(index)));
            } else {
                File file = (File) formData.valueAt(index);
                total = add(total, FILENAME.length());
                total = add(total, escapedLength(file.getName()));
                total = add(total, 3L);
                String type = file.getType().isEmpty() ? OCTET_STREAM : file.getType();
                total = add(total, CONTENT_TYPE.length());
                total = add(total, type.length());
                total = add(total, 2L);
                total = add(total, 2L);
                total = add(total, file.getSize());
            }
            total = add(total, 2L);
        }
        if (total > Integer.MAX_VALUE) {
            throw new JsRangeError("Buffered FormData exceeds Java byte[] limits");
        }

        byte[] output = new byte[(int) total];
        int cursor = 0;
        for (int index = 0; index < entryCount; index++) {
            cursor = writeAscii("--", output, cursor);
            cursor = writeAscii(boundary, output, cursor);
            cursor = writeAscii("\r\n", output, cursor);
            cursor = writeAscii(DISPOSITION, output, cursor);
            cursor = writeNormalizedEscaped(formData.nameAt(index), output, cursor);
            if (formData.kindAt(index) == FormData.stringKind()) {
                cursor = writeAscii("\"\r\n\r\n", output, cursor);
                cursor = writeNormalizedUtf8(
                        (String) formData.valueAt(index),
                        output,
                        cursor);
            } else {
                File file = (File) formData.valueAt(index);
                cursor = writeAscii(FILENAME, output, cursor);
                cursor = writeEscaped(file.getName(), output, cursor);
                cursor = writeAscii("\"\r\n", output, cursor);
                cursor = writeAscii(CONTENT_TYPE, output, cursor);
                cursor = writeAscii(
                        file.getType().isEmpty() ? OCTET_STREAM : file.getType(),
                        output,
                        cursor);
                cursor = writeAscii("\r\n\r\n", output, cursor);
                cursor = file.copyTo(output, cursor);
            }
            cursor = writeAscii("\r\n", output, cursor);
        }
        cursor = writeAscii("--", output, cursor);
        cursor = writeAscii(boundary, output, cursor);
        cursor = writeAscii("--\r\n", output, cursor);
        if (cursor != output.length) {
            throw new AssertionError(
                    "Multipart length mismatch: expected " + output.length + ", wrote " + cursor);
        }
        return BufferedBodySnapshot.fromOwnedBytes(
                output,
                "multipart/form-data; boundary=" + boundary);
    }

    private static void validateBoundary(String boundary) {
        int length = boundary.length();
        if (length < 27 || length > 70) {
            throw new JsTypeError("Multipart boundary length must be between 27 and 70 bytes");
        }
        for (int index = 0; index < length; index++) {
            char current = boundary.charAt(index);
            boolean valid = (current >= '0' && current <= '9')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z')
                    || current == '\''
                    || current == '-'
                    || current == '_';
            if (!valid) throw new JsTypeError("Invalid multipart boundary character");
        }
    }

    private static long partPrefixLength(String boundary) {
        return 2L + boundary.length() + 2L;
    }

    private static long closingLength(String boundary) {
        return 2L + boundary.length() + 4L;
    }

    private static long add(long total, long increment) {
        long result = total + increment;
        if (increment < 0L || result < total) {
            throw new JsRangeError("Buffered FormData size overflow");
        }
        return result;
    }

    private static long normalizedUtf8Length(String value) {
        long result = 0L;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r') {
                result += 2L;
                index++;
                if (index < value.length() && value.charAt(index) == '\n') index++;
            } else if (current == '\n') {
                result += 2L;
                index++;
            } else {
                int codePoint = codePointAt(value, index);
                result += utf8Length(codePoint);
                index += Character.charCount(codePoint);
            }
        }
        return result;
    }

    private static long normalizedEscapedLength(String value) {
        long result = 0L;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r') {
                result += 6L;
                index++;
                if (index < value.length() && value.charAt(index) == '\n') index++;
            } else if (current == '\n') {
                result += 6L;
                index++;
            } else if (current == '"') {
                result += 3L;
                index++;
            } else {
                int codePoint = codePointAt(value, index);
                result += utf8Length(codePoint);
                index += Character.charCount(codePoint);
            }
        }
        return result;
    }

    private static long escapedLength(String value) {
        long result = 0L;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r' || current == '\n' || current == '"') {
                result += 3L;
                index++;
            } else {
                int codePoint = codePointAt(value, index);
                result += utf8Length(codePoint);
                index += Character.charCount(codePoint);
            }
        }
        return result;
    }

    private static int writeNormalizedUtf8(String value, byte[] output, int offset) {
        int cursor = offset;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r') {
                output[cursor++] = '\r';
                output[cursor++] = '\n';
                index++;
                if (index < value.length() && value.charAt(index) == '\n') index++;
            } else if (current == '\n') {
                output[cursor++] = '\r';
                output[cursor++] = '\n';
                index++;
            } else {
                int codePoint = codePointAt(value, index);
                cursor = writeCodePoint(codePoint, output, cursor);
                index += Character.charCount(codePoint);
            }
        }
        return cursor;
    }

    private static int writeNormalizedEscaped(String value, byte[] output, int offset) {
        int cursor = offset;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r') {
                cursor = writeAscii("%0D%0A", output, cursor);
                index++;
                if (index < value.length() && value.charAt(index) == '\n') index++;
            } else if (current == '\n') {
                cursor = writeAscii("%0D%0A", output, cursor);
                index++;
            } else if (current == '"') {
                cursor = writeAscii("%22", output, cursor);
                index++;
            } else {
                int codePoint = codePointAt(value, index);
                cursor = writeCodePoint(codePoint, output, cursor);
                index += Character.charCount(codePoint);
            }
        }
        return cursor;
    }

    private static int writeEscaped(String value, byte[] output, int offset) {
        int cursor = offset;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r') {
                cursor = writeAscii("%0D", output, cursor);
                index++;
            } else if (current == '\n') {
                cursor = writeAscii("%0A", output, cursor);
                index++;
            } else if (current == '"') {
                cursor = writeAscii("%22", output, cursor);
                index++;
            } else {
                int codePoint = codePointAt(value, index);
                cursor = writeCodePoint(codePoint, output, cursor);
                index += Character.charCount(codePoint);
            }
        }
        return cursor;
    }

    private static int writeAscii(String value, byte[] output, int offset) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current > 0x7f) throw new AssertionError("Non-ASCII constant");
            output[offset++] = (byte) current;
        }
        return offset;
    }

    private static int codePointAt(String value, int index) {
        char first = value.charAt(index);
        if (Character.isHighSurrogate(first)
                && index + 1 < value.length()
                && Character.isLowSurrogate(value.charAt(index + 1))) {
            return Character.toCodePoint(first, value.charAt(index + 1));
        }
        return Character.isSurrogate(first) ? 0xfffd : first;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }

    private static int writeCodePoint(int codePoint, byte[] output, int offset) {
        if (codePoint <= 0x7f) {
            output[offset++] = (byte) codePoint;
        } else if (codePoint <= 0x7ff) {
            output[offset++] = (byte) (0xc0 | (codePoint >> 6));
            output[offset++] = (byte) (0x80 | (codePoint & 0x3f));
        } else if (codePoint <= 0xffff) {
            output[offset++] = (byte) (0xe0 | (codePoint >> 12));
            output[offset++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
            output[offset++] = (byte) (0x80 | (codePoint & 0x3f));
        } else {
            output[offset++] = (byte) (0xf0 | (codePoint >> 18));
            output[offset++] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
            output[offset++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
            output[offset++] = (byte) (0x80 | (codePoint & 0x3f));
        }
        return offset;
    }
}
