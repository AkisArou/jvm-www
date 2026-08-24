package io.github.akisarou.jvmwww.web.geometry;

/** Primitive-backed read-only rectangle with exact derived-edge semantics. */
public class DOMRectReadOnly {
    double x;
    double y;
    double width;
    double height;

    public DOMRectReadOnly() {
        this(0.0, 0.0, 0.0, 0.0);
    }

    public DOMRectReadOnly(double x) {
        this(x, 0.0, 0.0, 0.0);
    }

    public DOMRectReadOnly(double x, double y) {
        this(x, y, 0.0, 0.0);
    }

    public DOMRectReadOnly(double x, double y, double width) {
        this(x, y, width, 0.0);
    }

    public DOMRectReadOnly(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public static DOMRectReadOnly fromRect() {
        return new DOMRectReadOnly();
    }

    /** Creates a new rectangle by value; null represents the WebIDL empty dictionary. */
    public static DOMRectReadOnly fromRect(DOMRectReadOnly other) {
        return other == null
                ? new DOMRectReadOnly()
                : new DOMRectReadOnly(other.x, other.y, other.width, other.height);
    }

    /** Primitive compiler-facing form of DOMRectInit conversion. */
    public static DOMRectReadOnly fromRect(
            double x,
            double y,
            double width,
            double height) {
        return new DOMRectReadOnly(x, y, width, height);
    }

    public final double getX() {
        return x;
    }

    public final double getY() {
        return y;
    }

    public final double getWidth() {
        return width;
    }

    public final double getHeight() {
        return height;
    }

    public final double getTop() {
        return GeometryMath.minimum(y, y + height);
    }

    public final double getRight() {
        return GeometryMath.maximum(x, x + width);
    }

    public final double getBottom() {
        return GeometryMath.maximum(y, y + height);
    }

    public final double getLeft() {
        return GeometryMath.minimum(x, x + width);
    }
}
