package io.github.akisarou.jvmwww.web.nativeelements.android;

import android.os.Looper;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink;
import java.util.Objects;

/**
 * Owner-confined committed-layout table for Android/native renderers.
 *
 * <p>The renderer publishes primitive metadata and both transformed and untransformed border-box
 * snapshots on one Android Looper. Web reads resolve an opaque generation-safe identity directly
 * against those arrays. No Android View or renderer object is retained or exposed.</p>
 */
public final class AndroidNativeElementHost implements NativeElementHost, AutoCloseable {
    private static final int INITIAL_CAPACITY = 16;
    private static final int SLOT_BITS = 20;
    private static final long SLOT_MASK = (1L << SLOT_BITS) - 1L;
    private static final int MAX_SLOTS = (int) SLOT_MASK;
    private static final long MAX_GENERATION = Long.MAX_VALUE >>> SLOT_BITS;

    private static final byte STATE_ACTIVE = 1;
    private static final byte STATE_HAS_LAYOUT = 2;

    private static final int METADATA_STRIDE = 2;
    private static final int TAG_NAME_OFFSET = 0;
    private static final int ID_OFFSET = 1;

    private static final int LAYOUT_STRIDE = 8;
    private static final int TRANSFORMED_X = 0;
    private static final int TRANSFORMED_Y = 1;
    private static final int TRANSFORMED_WIDTH = 2;
    private static final int TRANSFORMED_HEIGHT = 3;
    private static final int UNTRANSFORMED_X = 4;
    private static final int UNTRANSFORMED_Y = 5;
    private static final int UNTRANSFORMED_WIDTH = 6;
    private static final int UNTRANSFORMED_HEIGHT = 7;

    private final Looper looper;

    private long[] generations;
    private int[] nextFreeSlot;
    private byte[] states;
    private String[] metadata;
    private double[] layout;
    private int firstFreeSlot = -1;
    private int nextUnusedSlot;
    private int activeCount;
    private boolean closed;

    private AndroidNativeElementHost(Looper looper) {
        this.looper = Objects.requireNonNull(looper, "looper");
    }

    /** Creates a committed-layout host attached to the calling thread's Android Looper. */
    public static AndroidNativeElementHost forCurrentLooper() {
        Looper looper = Looper.myLooper();
        if (looper == null) {
            throw new IllegalStateException("Current thread has no Android Looper");
        }
        return new AndroidNativeElementHost(looper);
    }

    public Looper getLooper() {
        return looper;
    }

    /**
     * Allocates one opaque generation-safe identity for a newly mounted renderer element.
     *
     * <p>The supplied immutable Strings are retained directly. Null metadata is normalized to the
     * empty string before publication.</p>
     */
    public long mountElement(String tagName, String id) {
        assertMutationAllowed();
        int slot = allocateSlot();
        int metadataIndex = slot * METADATA_STRIDE;
        metadata[metadataIndex + TAG_NAME_OFFSET] = tagName == null ? "" : tagName;
        metadata[metadataIndex + ID_OFFSET] = id == null ? "" : id;
        states[slot] = STATE_ACTIVE;
        activeCount++;
        return encodeIdentity(slot, generations[slot]);
    }

    /** Replaces the committed tag name and ID for one still-mounted identity. */
    public boolean commitMetadata(long elementIdentity, String tagName, String id) {
        assertMutationAllowed();
        int slot = resolveActiveSlot(elementIdentity);
        if (slot < 0) {
            return false;
        }
        int metadataIndex = slot * METADATA_STRIDE;
        metadata[metadataIndex + TAG_NAME_OFFSET] = tagName == null ? "" : tagName;
        metadata[metadataIndex + ID_OFFSET] = id == null ? "" : id;
        return true;
    }

    /**
     * Atomically replaces both transformed and untransformed committed border-box snapshots.
     *
     * <p>All values are unrestricted doubles and are stored without normalization.</p>
     */
    public boolean commitLayout(
            long elementIdentity,
            double transformedX,
            double transformedY,
            double transformedWidth,
            double transformedHeight,
            double untransformedX,
            double untransformedY,
            double untransformedWidth,
            double untransformedHeight) {
        assertMutationAllowed();
        int slot = resolveActiveSlot(elementIdentity);
        if (slot < 0) {
            return false;
        }
        int layoutIndex = slot * LAYOUT_STRIDE;
        layout[layoutIndex + TRANSFORMED_X] = transformedX;
        layout[layoutIndex + TRANSFORMED_Y] = transformedY;
        layout[layoutIndex + TRANSFORMED_WIDTH] = transformedWidth;
        layout[layoutIndex + TRANSFORMED_HEIGHT] = transformedHeight;
        layout[layoutIndex + UNTRANSFORMED_X] = untransformedX;
        layout[layoutIndex + UNTRANSFORMED_Y] = untransformedY;
        layout[layoutIndex + UNTRANSFORMED_WIDTH] = untransformedWidth;
        layout[layoutIndex + UNTRANSFORMED_HEIGHT] = untransformedHeight;
        states[slot] = (byte) (states[slot] | STATE_HAS_LAYOUT);
        return true;
    }

    /** Makes a mounted element temporarily layout-unavailable without changing its identity. */
    public boolean clearCommittedLayout(long elementIdentity) {
        assertMutationAllowed();
        int slot = resolveActiveSlot(elementIdentity);
        if (slot < 0) {
            return false;
        }
        states[slot] = (byte) (states[slot] & ~STATE_HAS_LAYOUT);
        return true;
    }

    /** Invalidates one exact mounted identity and makes its slot reusable with a new generation. */
    public boolean unmountElement(long elementIdentity) {
        assertMutationAllowed();
        int slot = resolveActiveSlot(elementIdentity);
        if (slot < 0) {
            return false;
        }

        states[slot] = 0;
        int metadataIndex = slot * METADATA_STRIDE;
        metadata[metadataIndex + TAG_NAME_OFFSET] = null;
        metadata[metadataIndex + ID_OFFSET] = null;
        activeCount--;

        long generation = generations[slot];
        if (generation < MAX_GENERATION) {
            generations[slot] = generation + 1L;
            nextFreeSlot[slot] = firstFreeSlot;
            firstFreeSlot = slot;
        }
        return true;
    }

    /** Number of mounted identities, exposed for renderer lifecycle diagnostics. */
    public int getActiveElementCount() {
        assertOwnerThread();
        return activeCount;
    }

    @Override
    public boolean isConnected(long elementIdentity) {
        assertOwnerThread();
        return !closed && resolveActiveSlot(elementIdentity) >= 0;
    }

    @Override
    public String getTagName(long elementIdentity) {
        assertOwnerThread();
        int slot = closed ? -1 : resolveActiveSlot(elementIdentity);
        return slot < 0 ? null : metadata[slot * METADATA_STRIDE + TAG_NAME_OFFSET];
    }

    @Override
    public String getId(long elementIdentity) {
        assertOwnerThread();
        int slot = closed ? -1 : resolveActiveSlot(elementIdentity);
        return slot < 0 ? null : metadata[slot * METADATA_STRIDE + ID_OFFSET];
    }

    @Override
    public boolean measureBoundingClientRect(
            long elementIdentity,
            boolean includeTransform,
            NativeElementRectSink sink) {
        assertOwnerThread();
        NativeElementRectSink checkedSink = Objects.requireNonNull(sink, "sink");
        int slot = closed ? -1 : resolveActiveSlot(elementIdentity);
        if (slot < 0 || (states[slot] & STATE_HAS_LAYOUT) == 0) {
            return false;
        }

        int layoutIndex = slot * LAYOUT_STRIDE;
        int offset = includeTransform ? TRANSFORMED_X : UNTRANSFORMED_X;
        checkedSink.setRect(
                layout[layoutIndex + offset],
                layout[layoutIndex + offset + 1],
                layout[layoutIndex + offset + 2],
                layout[layoutIndex + offset + 3]);
        return true;
    }

    /** Releases committed metadata and invalidates every identity on the owner Looper. */
    @Override
    public void close() {
        assertOwnerThread();
        if (closed) {
            return;
        }
        closed = true;
        generations = null;
        nextFreeSlot = null;
        states = null;
        metadata = null;
        layout = null;
        firstFreeSlot = -1;
        nextUnusedSlot = 0;
        activeCount = 0;
    }

    public boolean isClosed() {
        assertOwnerThread();
        return closed;
    }

    private int allocateSlot() {
        if (firstFreeSlot >= 0) {
            int slot = firstFreeSlot;
            firstFreeSlot = nextFreeSlot[slot];
            nextFreeSlot[slot] = -1;
            return slot;
        }
        if (nextUnusedSlot == MAX_SLOTS) {
            throw new IllegalStateException("Android native element table exhausted");
        }
        ensureCapacity(nextUnusedSlot + 1);
        int slot = nextUnusedSlot++;
        generations[slot] = 1L;
        nextFreeSlot[slot] = -1;
        return slot;
    }

    private void ensureCapacity(int requiredCapacity) {
        int currentCapacity = generations == null ? 0 : generations.length;
        if (requiredCapacity <= currentCapacity) {
            return;
        }

        int newCapacity = currentCapacity == 0 ? INITIAL_CAPACITY : currentCapacity << 1;
        if (newCapacity < requiredCapacity || newCapacity < 0) {
            newCapacity = requiredCapacity;
        }
        if (newCapacity > MAX_SLOTS) {
            newCapacity = MAX_SLOTS;
        }

        long[] newGenerations = new long[newCapacity];
        int[] newNextFreeSlot = new int[newCapacity];
        byte[] newStates = new byte[newCapacity];
        String[] newMetadata = new String[newCapacity * METADATA_STRIDE];
        double[] newLayout = new double[newCapacity * LAYOUT_STRIDE];
        if (currentCapacity != 0) {
            System.arraycopy(generations, 0, newGenerations, 0, currentCapacity);
            System.arraycopy(nextFreeSlot, 0, newNextFreeSlot, 0, currentCapacity);
            System.arraycopy(states, 0, newStates, 0, currentCapacity);
            System.arraycopy(metadata, 0, newMetadata, 0, currentCapacity * METADATA_STRIDE);
            System.arraycopy(layout, 0, newLayout, 0, currentCapacity * LAYOUT_STRIDE);
        }
        generations = newGenerations;
        nextFreeSlot = newNextFreeSlot;
        states = newStates;
        metadata = newMetadata;
        layout = newLayout;
    }

    private int resolveActiveSlot(long elementIdentity) {
        if (elementIdentity <= 0L || generations == null) {
            return -1;
        }
        long encodedSlot = elementIdentity & SLOT_MASK;
        if (encodedSlot == 0L) {
            return -1;
        }
        int slot = (int) encodedSlot - 1;
        if (slot < 0 || slot >= nextUnusedSlot || (states[slot] & STATE_ACTIVE) == 0) {
            return -1;
        }
        long generation = elementIdentity >>> SLOT_BITS;
        return generations[slot] == generation ? slot : -1;
    }

    private static long encodeIdentity(int slot, long generation) {
        return (generation << SLOT_BITS) | ((long) slot + 1L);
    }

    private void assertMutationAllowed() {
        assertOwnerThread();
        if (closed) {
            throw new IllegalStateException("Android native element host is closed");
        }
    }

    private void assertOwnerThread() {
        if (Looper.myLooper() != looper) {
            throw new IllegalStateException(
                    "Android native element host accessed outside its Looper thread");
        }
    }
}
