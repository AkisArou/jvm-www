package okhttp3;

import java.io.Closeable;
import java.io.IOException;

/** Deterministic test-only buffered response body with observable closure. */
public final class ResponseBody implements Closeable {
    private final byte[] bytes;
    private final IOException readFailure;
    private boolean closed;
    private Thread readThread;
    private Thread closeThread;

    public ResponseBody(byte[] bytes) {
        this(bytes, null);
    }

    public ResponseBody(byte[] bytes, IOException readFailure) {
        this.bytes = bytes == null ? new byte[0] : bytes.clone();
        this.readFailure = readFailure;
    }

    public byte[] bytes() throws IOException {
        readThread = Thread.currentThread();
        if (readFailure != null) {
            throw readFailure;
        }
        return bytes.clone();
    }

    public boolean isClosed() {
        return closed;
    }

    public Thread getReadThread() {
        return readThread;
    }

    public Thread getCloseThread() {
        return closeThread;
    }

    @Override
    public void close() {
        closeThread = Thread.currentThread();
        closed = true;
    }
}
