package io.github.akisarou.jvmwww.web.geometry;

/** Primitive-backed read-only point from the selected Geometry Interfaces profile. */
public class DOMPointReadOnly {
    double x;
    double y;
    double z;
    double w;

    public DOMPointReadOnly() { this(0.0, 0.0, 0.0, 1.0); }
    public DOMPointReadOnly(double x) { this(x, 0.0, 0.0, 1.0); }
    public DOMPointReadOnly(double x, double y) { this(x, y, 0.0, 1.0); }
    public DOMPointReadOnly(double x, double y, double z) { this(x, y, z, 1.0); }
    public DOMPointReadOnly(double x, double y, double z, double w) {
        this.x = x; this.y = y; this.z = z; this.w = w;
    }

    public static DOMPointReadOnly fromPoint() { return new DOMPointReadOnly(); }
    public static DOMPointReadOnly fromPoint(DOMPointReadOnly other) {
        return other == null ? new DOMPointReadOnly() : new DOMPointReadOnly(other.x, other.y, other.z, other.w);
    }
    public static DOMPointReadOnly fromPoint(double x, double y, double z, double w) {
        return new DOMPointReadOnly(x, y, z, w);
    }

    public final double getX() { return x; }
    public final double getY() { return y; }
    public final double getZ() { return z; }
    public final double getW() { return w; }

    /** Applies the supplied matrix dictionary, or the identity matrix for a null/empty dictionary. */
    public DOMPoint matrixTransform(DOMMatrixReadOnly matrix) {
        return matrix == null ? new DOMPoint(x, y, z, w) : matrix.transformPoint(this);
    }
}
