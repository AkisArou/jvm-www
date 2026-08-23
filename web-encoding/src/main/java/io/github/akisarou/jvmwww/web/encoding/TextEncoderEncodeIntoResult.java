package io.github.akisarou.jvmwww.web.encoding;

/** Progress returned by {@link TextEncoder#encodeInto(String, byte[])}. */
public final class TextEncoderEncodeIntoResult {
    private final long read;
    private final long written;

    TextEncoderEncodeIntoResult(long read, long written) {
        this.read = read;
        this.written = written;
    }

    /** Number of UTF-16 source code units converted. */
    public long getRead() {
        return read;
    }

    /** Number of destination bytes written. */
    public long getWritten() {
        return written;
    }
}
