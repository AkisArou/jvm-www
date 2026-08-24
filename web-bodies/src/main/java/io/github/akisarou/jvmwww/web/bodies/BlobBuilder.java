package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.encoding.Utf8Codec;
import java.util.Arrays;
import java.util.Objects;

/** Compiler-facing allocation-aware builder for a heterogeneous BlobPart sequence. */
public final class BlobBuilder {
    private final RuntimeInstance runtime;
    private byte[][] segments;
    private int[] offsets;
    private int[] lengths;
    private int count;
    private long size;
    private boolean built;

    public BlobBuilder(RuntimeInstance runtime) {
        this(runtime, 4);
    }

    public BlobBuilder(RuntimeInstance runtime, int expectedSegmentCount) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        BodyRuntimeChecks.assertLanguageExecution(runtime);
        if (expectedSegmentCount < 0) {
            throw new IllegalArgumentException("expectedSegmentCount must be non-negative");
        }
        int capacity = Math.max(1, expectedSegmentCount);
        segments = new byte[capacity][];
        offsets = new int[capacity];
        lengths = new int[capacity];
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public long getSize() {
        assertOpen();
        return size;
    }

    public BlobBuilder appendBytes(byte[] bytes) {
        byte[] checked = Objects.requireNonNull(bytes, "bytes");
        return appendBytes(checked, 0, checked.length);
    }

    public BlobBuilder appendBytes(byte[] bytes, int offset, int length) {
        assertOpen();
        byte[] checked = Objects.requireNonNull(bytes, "bytes");
        if (offset < 0 || length < 0 || offset > checked.length - length) {
            throw new IndexOutOfBoundsException("Invalid byte source range");
        }
        if (length == 0) return this;
        byte[] copy = Arrays.copyOfRange(checked, offset, offset + length);
        appendOwnedSegment(copy);
        return this;
    }

    public BlobBuilder appendText(String text) {
        assertOpen();
        byte[] encoded = Utf8Codec.encode(Objects.requireNonNull(text, "text"));
        if (encoded.length != 0) appendOwnedSegment(encoded);
        return this;
    }

    public BlobBuilder appendBlob(Blob blob) {
        assertOpen();
        Blob checked = Objects.requireNonNull(blob, "blob");
        checked.assertAccess();
        if (checked.getRuntime() != runtime) {
            throw new IllegalArgumentException("Blob part belongs to another RuntimeInstance");
        }
        checked.data().appendTo(this);
        return this;
    }

    public Blob build() {
        return build("");
    }

    public Blob build(String type) {
        assertOpen();
        built = true;
        return new Blob(runtime, takeData(), type);
    }

    public File buildFile(String name) {
        return buildFile(name, "", System.currentTimeMillis());
    }

    public File buildFile(String name, String type) {
        return buildFile(name, type, System.currentTimeMillis());
    }

    public File buildFile(String name, String type, long lastModified) {
        assertOpen();
        built = true;
        return new File(runtime, takeData(), type, name, lastModified);
    }

    void appendSharedSegment(byte[] bytes, int offset, int length) {
        if (length == 0) return;
        ensureCapacity(count + 1);
        segments[count] = bytes;
        offsets[count] = offset;
        lengths[count] = length;
        count++;
        size = addSize(size, length);
    }

    private void appendOwnedSegment(byte[] bytes) {
        appendSharedSegment(bytes, 0, bytes.length);
    }

    private BlobData takeData() {
        BlobData result = BlobData.take(segments, offsets, lengths, count, size);
        segments = null;
        offsets = null;
        lengths = null;
        count = 0;
        size = 0L;
        return result;
    }

    private void ensureCapacity(int required) {
        if (required <= segments.length) return;
        int current = segments.length;
        int grown = current + (current >> 1) + 1;
        int capacity = Math.max(required, grown);
        segments = Arrays.copyOf(segments, capacity);
        offsets = Arrays.copyOf(offsets, capacity);
        lengths = Arrays.copyOf(lengths, capacity);
    }

    private void assertOpen() {
        BodyRuntimeChecks.assertLanguageExecution(runtime);
        if (built) throw new IllegalStateException("BlobBuilder is already consumed");
    }

    private static long addSize(long current, int increment) {
        long result = current + increment;
        if (result < current) throw new OutOfMemoryError("Blob size overflow");
        return result;
    }
}
