package io.github.akisarou.jvmwww.web.nativeelements.testkit;

import io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;

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
    long identity;
    boolean includeTransform;
    int calls;
    int mode;
    boolean retain;
    NativeElementRectSink retained;
    ReactNativeElement reentrant;

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
            reentrant.getBoundingClientRect();
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

    static final class Marker extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
