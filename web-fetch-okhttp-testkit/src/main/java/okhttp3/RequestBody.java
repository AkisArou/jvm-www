package okhttp3;

import java.util.Objects;

/** Deterministic test-only buffered request body. */
public final class RequestBody {
    public static final RequestBody EMPTY = new RequestBody(new byte[0]);

    private final byte[] bytes;

    private RequestBody(byte[] bytes) {
        this.bytes = bytes;
    }

    public static RequestBody create(byte[] bytes, MediaType mediaType) {
        Objects.requireNonNull(bytes, "bytes");
        return new RequestBody(bytes.clone());
    }

    public byte[] copyBytes() {
        return bytes.clone();
    }
}
