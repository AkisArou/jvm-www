package io.github.akisarou.jvmwww.web.bodies;

import java.nio.ByteBuffer;
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

    /**
     * Returns a fresh read-only view over the immutable bytes without copying the payload.
     *
     * <p>The view has independent position and limit state. Transport adapters may pass it to APIs
     * that copy directly into their own immutable representation.</p>
     */
    public ByteBuffer asReadOnlyByteBuffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    byte[] ownedBytes() {
        return bytes;
    }
}
