package io.github.akisarou.jvmwww.web.nativeelements.testkit;

import io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;
import java.util.Objects;

/** Deliberately adversarial synchronous renderer double. */
final class FakeNativeElementHost implements NativeElementHost {
    static final int NORMAL = 0;
    static final int NO_WRITE = 1;
    static final int FALSE_WRITE = 2;
    static final int TWICE = 3;
    static final int THROW = 4;
    static final int REENTER = 5;

    boolean connected;
    String tag = "RN:View";
    String id = "";
    boolean available;
    double x;
    double y;
    double transformedWidth;
    double transformedHeight;
    double width;
    double height;
    boolean offsetAvailable;
    boolean hasOffsetParent;
    long offsetParentIdentity;
    double offsetTop;
    double offsetLeft;
    boolean metricsAvailable;
    double clientWidth;
    double clientHeight;
    double clientTop;
    double clientLeft;
    double scrollLeft;
    double scrollTop;
    double scrollWidth;
    double scrollHeight;
    long identity;
    boolean includeTransform;
    int calls;
    int mode;
    int offsetMode;
    boolean retain;
    boolean retainOffset;
    NativeElementRectSink retained;
    NativeElementOffsetSink retainedOffset;
    ReactNativeElement reentrant;

    private long[] publicIdentities = new long[4];
    private ReactNativeElement[] publicInstances = new ReactNativeElement[4];
    private int publicCount;

    @Override
    public ReactNativeElement getPublicInstance(long value) {
        calls++;
        identity = value;
        for (int index = 0; index < publicCount; index++) {
            if (publicIdentities[index] == value) {
                return publicInstances[index];
            }
        }
        return null;
    }

    @Override
    public ReactNativeElement registerPublicInstance(
            long value, ReactNativeElement publicInstance) {
        calls++;
        identity = value;
        ReactNativeElement checked = Objects.requireNonNull(publicInstance, "publicInstance");
        for (int index = 0; index < publicCount; index++) {
            if (publicIdentities[index] == value) {
                if (publicInstances[index] != checked) {
                    throw new IllegalStateException("different public instance");
                }
                return checked;
            }
        }
        if (publicCount == publicIdentities.length) {
            int newLength = publicCount << 1;
            long[] newIdentities = new long[newLength];
            ReactNativeElement[] newInstances = new ReactNativeElement[newLength];
            System.arraycopy(publicIdentities, 0, newIdentities, 0, publicCount);
            System.arraycopy(publicInstances, 0, newInstances, 0, publicCount);
            publicIdentities = newIdentities;
            publicInstances = newInstances;
        }
        publicIdentities[publicCount] = value;
        publicInstances[publicCount] = checked;
        publicCount++;
        return checked;
    }

    @Override
    public boolean isConnected(long value) {
        calls++;
        identity = value;
        return connected;
    }

    @Override
    public String getTagName(long value) {
        calls++;
        identity = value;
        return tag;
    }

    @Override
    public String getId(long value) {
        calls++;
        identity = value;
        return id;
    }

    @Override
    public boolean measureBoundingClientRect(
            long value, boolean transformed, NativeElementRectSink sink) {
        calls++;
        identity = value;
        includeTransform = transformed;
        if (retain) retained = sink;
        if (mode == THROW) throw new Marker();
        if (mode == NO_WRITE) return true;
        if (mode == REENTER) {
            reentrant.getOffsetTop();
            throw new AssertionError("reentrant call returned");
        }
        double selectedWidth = transformed ? transformedWidth : width;
        double selectedHeight = transformed ? transformedHeight : height;
        if (mode == FALSE_WRITE || mode == TWICE || available) {
            sink.setRect(x, y, selectedWidth, selectedHeight);
        }
        if (mode == TWICE) sink.setRect(0.0, 0.0, 0.0, 0.0);
        return mode != FALSE_WRITE && available;
    }

    @Override
    public boolean measureOffset(long value, NativeElementOffsetSink sink) {
        calls++;
        identity = value;
        if (retainOffset) retainedOffset = sink;
        if (offsetMode == THROW) throw new Marker();
        if (offsetMode == NO_WRITE) return true;
        if (offsetMode == REENTER) {
            reentrant.getBoundingClientRect();
            throw new AssertionError("reentrant call returned");
        }
        if (offsetMode == FALSE_WRITE || offsetMode == TWICE || offsetAvailable) {
            sink.setOffset(hasOffsetParent, offsetParentIdentity, offsetTop, offsetLeft);
        }
        if (offsetMode == TWICE) sink.setOffset(false, 0L, 0.0, 0.0);
        return offsetMode != FALSE_WRITE && offsetAvailable;
    }

    @Override public double getClientWidth(long value) { return metric(value, clientWidth); }
    @Override public double getClientHeight(long value) { return metric(value, clientHeight); }
    @Override public double getClientTop(long value) { return metric(value, clientTop); }
    @Override public double getClientLeft(long value) { return metric(value, clientLeft); }
    @Override public double getScrollLeft(long value) { return metric(value, scrollLeft); }
    @Override public double getScrollTop(long value) { return metric(value, scrollTop); }
    @Override public double getScrollWidth(long value) { return metric(value, scrollWidth); }
    @Override public double getScrollHeight(long value) { return metric(value, scrollHeight); }

    private double metric(long value, double metricValue) {
        calls++;
        identity = value;
        return metricsAvailable ? metricValue : 0.0;
    }

    static final class Marker extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
