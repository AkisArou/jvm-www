package io.github.akisarou.jvmwww.web.geometry;

/** Mutable DOMPoint using the same four primitive coordinates as DOMPointReadOnly. */
public final class DOMPoint extends DOMPointReadOnly {
    public DOMPoint() {
        super();
    }

    public DOMPoint(double x) {
        super(x);
    }

    public DOMPoint(double x, double y) {
        super(x, y);
    }

    public DOMPoint(double x, double y, double z) {
        super(x, y, z);
    }

    public DOMPoint(double x, double y, double z, double w) {
        super(x, y, z, w);
    }

    public static DOMPoint fromPoint() {
        return new DOMPoint();
    }

    /** Creates an independent mutable point; null represents the empty dictionary. */
    public static DOMPoint fromPoint(DOMPointReadOnly other) {
        return other == null
                ? new DOMPoint()
                : new DOMPoint(other.x, other.y, other.z, other.w);
    }

    /** Primitive compiler-facing form of DOMPointInit conversion. */
    public static DOMPoint fromPoint(double x, double y, double z, double w) {
        return new DOMPoint(x, y, z, w);
    }

    public void setX(double value) {
        x = value;
    }

    public void setY(double value) {
        y = value;
    }

    public void setZ(double value) {
        z = value;
    }

    public void setW(double value) {
        w = value;
    }
}
