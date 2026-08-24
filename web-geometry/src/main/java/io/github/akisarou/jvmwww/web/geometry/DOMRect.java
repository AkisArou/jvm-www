package io.github.akisarou.jvmwww.web.geometry;

/** Mutable DOMRect using the same primitive storage as DOMRectReadOnly. */
public final class DOMRect extends DOMRectReadOnly {
    public DOMRect() {
        super();
    }

    public DOMRect(double x) {
        super(x);
    }

    public DOMRect(double x, double y) {
        super(x, y);
    }

    public DOMRect(double x, double y, double width) {
        super(x, y, width);
    }

    public DOMRect(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    public static DOMRect fromRect() {
        return new DOMRect();
    }

    /** Creates an independent mutable rectangle; null represents the empty dictionary. */
    public static DOMRect fromRect(DOMRectReadOnly other) {
        return other == null
                ? new DOMRect()
                : new DOMRect(other.x, other.y, other.width, other.height);
    }

    /** Primitive compiler-facing form of DOMRectInit conversion. */
    public static DOMRect fromRect(double x, double y, double width, double height) {
        return new DOMRect(x, y, width, height);
    }

    public void setX(double value) {
        x = value;
    }

    public void setY(double value) {
        y = value;
    }

    public void setWidth(double value) {
        width = value;
    }

    public void setHeight(double value) {
        height = value;
    }
}
