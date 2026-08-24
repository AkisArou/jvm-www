package io.github.akisarou.jvmwww.web.encoding;

final class Utf8 {
    private Utf8() {}

    static int encodedLength(String source) {
        long length = 0L;
        int index = 0;
        while (index < source.length()) {
            char first = source.charAt(index);
            if (first <= 0x7f) {
                length++;
                index++;
            } else if (first <= 0x7ff) {
                length += 2L;
                index++;
            } else if (Character.isHighSurrogate(first)
                    && index + 1 < source.length()
                    && Character.isLowSurrogate(source.charAt(index + 1))) {
                length += 4L;
                index += 2;
            } else {
                length += 3L;
                index++;
            }
            if (length > Integer.MAX_VALUE) {
                throw new OutOfMemoryError("UTF-8 encoded output exceeds Java array limits");
            }
        }
        return (int) length;
    }

    static long encodeInto(String source, byte[] destination) {
        return encodeInto(source, destination, 0);
    }

    static long encodeInto(String source, byte[] destination, int offset) {
        if (offset < 0 || offset > destination.length) {
            throw new IndexOutOfBoundsException("offset: " + offset);
        }
        int read = 0;
        int written = 0;
        while (read < source.length()) {
            char first = source.charAt(read);
            int codePoint;
            int codeUnits;
            if (Character.isHighSurrogate(first)
                    && read + 1 < source.length()
                    && Character.isLowSurrogate(source.charAt(read + 1))) {
                codePoint = Character.toCodePoint(first, source.charAt(read + 1));
                codeUnits = 2;
            } else if (Character.isSurrogate(first)) {
                codePoint = 0xfffd;
                codeUnits = 1;
            } else {
                codePoint = first;
                codeUnits = 1;
            }

            int byteCount = byteCount(codePoint);
            if (destination.length - offset - written < byteCount) {
                break;
            }
            writeCodePoint(codePoint, destination, offset + written);
            read += codeUnits;
            written += byteCount;
        }
        return ((long) read << 32) | (written & 0xffffffffL);
    }

    private static int byteCount(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }

    private static void writeCodePoint(int codePoint, byte[] output, int offset) {
        if (codePoint <= 0x7f) {
            output[offset] = (byte) codePoint;
        } else if (codePoint <= 0x7ff) {
            output[offset] = (byte) (0xc0 | (codePoint >> 6));
            output[offset + 1] = (byte) (0x80 | (codePoint & 0x3f));
        } else if (codePoint <= 0xffff) {
            output[offset] = (byte) (0xe0 | (codePoint >> 12));
            output[offset + 1] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
            output[offset + 2] = (byte) (0x80 | (codePoint & 0x3f));
        } else {
            output[offset] = (byte) (0xf0 | (codePoint >> 18));
            output[offset + 1] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
            output[offset + 2] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
            output[offset + 3] = (byte) (0x80 | (codePoint & 0x3f));
        }
    }
}
