package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.JsRangeError;
import java.util.Arrays;

/** Immutable segmented byte storage. Segment byte arrays are never exposed or mutated. */
final class BlobData {
    private static final byte[][] NO_SEGMENTS = new byte[0][];
    private static final int[] NO_INTS = new int[0];

    private final byte[][] segments;
    private final int[] offsets;
    private final int[] lengths;
    private final long size;

    private BlobData(byte[][] segments, int[] offsets, int[] lengths, long size) {
        this.segments = segments;
        this.offsets = offsets;
        this.lengths = lengths;
        this.size = size;
    }

    static BlobData empty() {
        return new BlobData(NO_SEGMENTS, NO_INTS, NO_INTS, 0L);
    }

    static BlobData singleOwned(byte[] bytes) {
        if (bytes.length == 0) return empty();
        return new BlobData(
                new byte[][] {bytes},
                new int[] {0},
                new int[] {bytes.length},
                bytes.length);
    }

    static BlobData take(
            byte[][] segments,
            int[] offsets,
            int[] lengths,
            int count,
            long size) {
        if (count == 0) return empty();
        return new BlobData(
                count == segments.length ? segments : Arrays.copyOf(segments, count),
                count == offsets.length ? offsets : Arrays.copyOf(offsets, count),
                count == lengths.length ? lengths : Arrays.copyOf(lengths, count),
                size);
    }

    long size() {
        return size;
    }

    int segmentCount() {
        return segments.length;
    }

    void appendTo(BlobBuilder builder) {
        for (int index = 0; index < segments.length; index++) {
            builder.appendSharedSegment(segments[index], offsets[index], lengths[index]);
        }
    }

    BlobData slice(long start, long end) {
        if (start >= end || size == 0L) return empty();
        int count = 0;
        long position = 0L;
        for (int index = 0; index < segments.length; index++) {
            long next = position + lengths[index];
            if (next > start && position < end) count++;
            position = next;
            if (position >= end) break;
        }

        byte[][] slicedSegments = new byte[count][];
        int[] slicedOffsets = new int[count];
        int[] slicedLengths = new int[count];
        int written = 0;
        position = 0L;
        for (int index = 0; index < segments.length && written < count; index++) {
            int segmentLength = lengths[index];
            long next = position + segmentLength;
            if (next > start && position < end) {
                int localStart = (int) Math.max(0L, start - position);
                int localEnd = (int) Math.min((long) segmentLength, end - position);
                slicedSegments[written] = segments[index];
                slicedOffsets[written] = offsets[index] + localStart;
                slicedLengths[written] = localEnd - localStart;
                written++;
            }
            position = next;
        }
        return new BlobData(slicedSegments, slicedOffsets, slicedLengths, end - start);
    }

    byte[] copyBytes() {
        if (size > Integer.MAX_VALUE) {
            throw new JsRangeError("Buffered Blob exceeds Java byte[] limits");
        }
        byte[] output = new byte[(int) size];
        copyTo(output, 0);
        return output;
    }

    int copyTo(byte[] output, int outputOffset) {
        if (outputOffset < 0 || size > output.length - (long) outputOffset) {
            throw new IndexOutOfBoundsException("Blob output range does not fit destination");
        }
        int cursor = outputOffset;
        for (int index = 0; index < segments.length; index++) {
            int length = lengths[index];
            System.arraycopy(segments[index], offsets[index], output, cursor, length);
            cursor += length;
        }
        return cursor;
    }
}
