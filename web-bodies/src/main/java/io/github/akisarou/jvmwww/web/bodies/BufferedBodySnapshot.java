package io.github.akisarou.jvmwww.web.bodies;

import java.util.Objects;

/** Immutable transport-safe bytes plus an optional inferred Content-Type. */
public final class BufferedBodySnapshot {
    private final byte[] bytes;
    private final String contentType;

    private BufferedBodySnapshot(byte[] bytes, String contentType) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.contentType = contentType;
    }

    public static BufferedBodySnapshot copyOf(byte[] bytes, String contentType) {
        return new BufferedBodySnapshot(
                Objects.requireNonNull(bytes, "bytes").clone(),
                contentType);
    }

    /** Ownership-transfer ABI for a freshly allocated array that will not be mutated again. */
    public static BufferedBodySnapshot fromOwnedBytes(byte[] bytes, String contentType) {
        return new BufferedBodySnapshot(bytes, contentType);
    }

    public long getSize() {
        return bytes.length;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] copyBytes() {
        return bytes.clone();
    }

    byte[] ownedBytes() {
        return bytes;
    }
}
