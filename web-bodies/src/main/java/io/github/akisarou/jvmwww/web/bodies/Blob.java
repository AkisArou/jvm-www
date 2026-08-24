package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.web.encoding.TextDecoder;
import io.github.akisarou.jvmwww.web.encoding.Utf8Codec;
import java.util.Objects;

/** Owner-confined immutable Blob backed by shareable immutable byte segments. */
public class Blob implements BufferedBodySource {
    private static final int READ_BYTES = 1;
    private static final int READ_TEXT = 2;

    private final RuntimeInstance runtime;
    private final BlobData data;
    private final String type;

    public Blob(RuntimeInstance runtime) {
        this(runtime, BlobData.empty(), "");
    }

    public Blob(RuntimeInstance runtime, byte[] bytes) {
        this(runtime, bytes, "");
    }

    public Blob(RuntimeInstance runtime, byte[] bytes, String type) {
        this(
                checkedRuntime(runtime),
                BlobData.singleOwned(Objects.requireNonNull(bytes, "bytes").clone()),
                type);
    }

    public Blob(RuntimeInstance runtime, String text) {
        this(runtime, text, "");
    }

    public Blob(RuntimeInstance runtime, String text, String type) {
        this(
                checkedRuntime(runtime),
                BlobData.singleOwned(Utf8Codec.encode(Objects.requireNonNull(text, "text"))),
                type);
    }

    Blob(RuntimeInstance runtime, BlobData data, String type) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        BodyRuntimeChecks.assertLanguageExecution(runtime);
        this.data = Objects.requireNonNull(data, "data");
        this.type = normalizeType(type);
    }

    /** Shares the immutable storage of a body snapshot without another full byte copy. */
    public static Blob fromSnapshot(
            RuntimeInstance runtime,
            BufferedBodySnapshot snapshot,
            String type) {
        RuntimeInstance checkedRuntime = checkedRuntime(runtime);
        BufferedBodySnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        return new Blob(
                checkedRuntime,
                BlobData.singleOwned(checkedSnapshot.ownedBytes()),
                type);
    }

    @Override
    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public long getSize() {
        assertAccess();
        return data.size();
    }

    public String getType() {
        assertAccess();
        return type;
    }

    public Blob slice() {
        return slice(0L, data.size(), "");
    }

    public Blob slice(long start) {
        return slice(start, data.size(), "");
    }

    public Blob slice(long start, long end) {
        return slice(start, end, "");
    }

    public Blob slice(long start, long end, String contentType) {
        assertAccess();
        long size = data.size();
        long relativeStart = normalizePosition(start, size);
        long relativeEnd = normalizePosition(end, size);
        long spanEnd = Math.max(relativeStart, relativeEnd);
        return new Blob(runtime, data.slice(relativeStart, spanEnd), contentType);
    }

    public JsPromise arrayBuffer() {
        return startRead(READ_BYTES);
    }

    /** Current Java ABI returns an independent byte[] for both bytes() and arrayBuffer(). */
    public JsPromise bytes() {
        return startRead(READ_BYTES);
    }

    public JsPromise text() {
        return startRead(READ_TEXT);
    }

    @Override
    public BufferedBodySnapshot snapshot() {
        assertAccess();
        return BufferedBodySnapshot.fromOwnedBytes(
                data.copyBytes(),
                type.isEmpty() ? null : type);
    }

    final BlobData data() {
        return data;
    }

    final int copyTo(byte[] output, int offset) {
        assertAccess();
        return data.copyTo(output, offset);
    }

    final void assertAccess() {
        BodyRuntimeChecks.assertLanguageExecution(runtime);
    }

    private JsPromise startRead(int kind) {
        assertAccess();
        BlobReadPromise promise = new BlobReadPromise(runtime, data, kind);
        runtime.queueMicrotask(promise);
        return promise;
    }

    private static RuntimeInstance checkedRuntime(RuntimeInstance runtime) {
        RuntimeInstance checked = Objects.requireNonNull(runtime, "runtime");
        BodyRuntimeChecks.assertLanguageExecution(checked);
        return checked;
    }

    private static long normalizePosition(long value, long size) {
        if (value < 0L) {
            long shifted = size + value;
            return shifted < 0L ? 0L : shifted;
        }
        return value > size ? size : value;
    }

    static String normalizeType(String value) {
        String checked = value == null ? "" : value;
        char[] lowered = null;
        for (int index = 0; index < checked.length(); index++) {
            char current = checked.charAt(index);
            if (current < 0x20 || current > 0x7e) return "";
            if (current >= 'A' && current <= 'Z') {
                if (lowered == null) lowered = checked.toCharArray();
                lowered[index] = (char) (current + ('a' - 'A'));
            }
        }
        return lowered == null ? checked : new String(lowered);
    }

    private static final class BlobReadPromise extends JsPromise implements RuntimeTask {
        private BlobData data;
        private final int kind;

        BlobReadPromise(RuntimeInstance runtime, BlobData data, int kind) {
            super(runtime);
            this.data = data;
            this.kind = kind;
        }

        @Override
        public void execute(RuntimeInstance runtime) {
            BlobData captured = data;
            data = null;
            try {
                byte[] bytes = captured.copyBytes();
                if (kind == READ_BYTES) {
                    fulfillReference(bytes);
                } else if (kind == READ_TEXT) {
                    fulfillReference(new TextDecoder(runtime).decode(bytes));
                } else {
                    throw new AssertionError("Unknown Blob read kind: " + kind);
                }
            } catch (Throwable error) {
                rethrowIfFatal(error);
                rejectReference(error);
            }
        }

        @Override
        public void discard() {
            data = null;
        }

        private static void rethrowIfFatal(Throwable error) {
            if (error instanceof ThreadDeath) throw (ThreadDeath) error;
            if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
            if (error instanceof LinkageError) throw (LinkageError) error;
        }
    }
}
