package io.github.akisarou.jvmwww.web.nativeelements;

/** Synchronous primitive destination for one renderer-owned element rectangle snapshot. */
public interface NativeElementRectSink {
    void setRect(double x, double y, double width, double height);
}
