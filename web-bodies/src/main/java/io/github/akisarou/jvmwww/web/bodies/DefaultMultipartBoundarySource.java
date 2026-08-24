package io.github.akisarou.jvmwww.web.bodies;

import java.util.UUID;

/** Default 122-bit random multipart boundary source with no module-owned mutable state. */
final class DefaultMultipartBoundarySource implements MultipartBoundarySource {
    static final DefaultMultipartBoundarySource INSTANCE = new DefaultMultipartBoundarySource();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String PREFIX = "----jvmwww-";

    private DefaultMultipartBoundarySource() {}

    @Override
    public String nextBoundary() {
        UUID value = UUID.randomUUID();
        char[] output = new char[PREFIX.length() + 32];
        PREFIX.getChars(0, PREFIX.length(), output, 0);
        writeHex(value.getMostSignificantBits(), output, PREFIX.length());
        writeHex(value.getLeastSignificantBits(), output, PREFIX.length() + 16);
        return new String(output);
    }

    private static void writeHex(long value, char[] output, int offset) {
        for (int index = 15; index >= 0; index--) {
            output[offset + index] = HEX[(int) (value & 0x0fL)];
            value >>>= 4;
        }
    }
}
