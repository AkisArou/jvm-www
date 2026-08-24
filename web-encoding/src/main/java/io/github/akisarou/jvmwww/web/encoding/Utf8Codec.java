package io.github.akisarou.jvmwww.web.encoding;

import java.util.Objects;

/** Allocation-aware UTF-8 scalar codec shared by Web capability modules. */
public final class Utf8Codec {
    private Utf8Codec() {}

    public static int encodedLength(String source) {
        return Utf8.encodedLength(Objects.requireNonNull(source, "source"));
    }

    public static byte[] encode(String source) {
        String checked = Objects.requireNonNull(source, "source");
        byte[] output = new byte[Utf8.encodedLength(checked)];
        long progress = Utf8.encodeInto(checked, output);
        if ((int) (progress >>> 32) != checked.length()
                || (int) progress != output.length) {
            throw new AssertionError("Exact UTF-8 output length was not filled");
        }
        return output;
    }

    /**
     * Encodes complete scalar values into {@code destination} beginning at {@code offset}.
     * The high 32 bits are UTF-16 code units read; the low 32 bits are bytes written.
     */
    public static long encodeInto(String source, byte[] destination, int offset) {
        return Utf8.encodeInto(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(destination, "destination"),
                offset);
    }
}
