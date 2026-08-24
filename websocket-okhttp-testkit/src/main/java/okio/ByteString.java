package okio;

import java.nio.ByteBuffer;

public final class ByteString {
    public static int ofByteBufferCalls;
    public static int ofByteArrayCalls;
    private final byte[] data;

    private ByteString(byte[] data) {
        this.data = data;
    }

    public static ByteString of(ByteBuffer source) {
        ofByteBufferCalls++;
        byte[] copy = new byte[source.remaining()];
        source.get(copy);
        return new ByteString(copy);
    }

    public static ByteString of(byte... source) {
        ofByteArrayCalls++;
        return new ByteString(source.clone());
    }

    public static ByteString testOwned(byte[] source) {
        return new ByteString(source.clone());
    }

    public byte[] toByteArray() {
        return data.clone();
    }
}
