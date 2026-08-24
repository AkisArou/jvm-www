package io.github.akisarou.jvmwww.web.geometry;

/** Quadrilateral with four same-object mutable points and allocation-bounded bounds calculation. */
public final class DOMQuad {
    private final DOMPoint p1;
    private final DOMPoint p2;
    private final DOMPoint p3;
    private final DOMPoint p4;

    public DOMQuad() {
        this(null, null, null, null);
    }

    public DOMQuad(DOMPointReadOnly p1) {
        this(p1, null, null, null);
    }

    public DOMQuad(DOMPointReadOnly p1, DOMPointReadOnly p2) {
        this(p1, p2, null, null);
    }

    public DOMQuad(
            DOMPointReadOnly p1,
            DOMPointReadOnly p2,
            DOMPointReadOnly p3) {
        this(p1, p2, p3, null);
    }

    public DOMQuad(
            DOMPointReadOnly p1,
            DOMPointReadOnly p2,
            DOMPointReadOnly p3,
            DOMPointReadOnly p4) {
        this(copyPoint(p1), copyPoint(p2), copyPoint(p3), copyPoint(p4), 0);
    }

    private DOMQuad(
            DOMPoint p1,
            DOMPoint p2,
            DOMPoint p3,
            DOMPoint p4,
            int ownedMarker) {
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
        this.p4 = p4;
    }

    public static DOMQuad fromRect() {
        return new DOMQuad();
    }

    /** Creates a quad from a rectangle dictionary; null represents the empty dictionary. */
    public static DOMQuad fromRect(DOMRectReadOnly other) {
        if (other == null) {
            return new DOMQuad();
        }
        return fromRect(other.x, other.y, other.width, other.height);
    }

    /** Primitive compiler-facing form of DOMRectInit conversion. */
    public static DOMQuad fromRect(double x, double y, double width, double height) {
        double right = x + width;
        double bottom = y + height;
        return new DOMQuad(
                new DOMPoint(x, y),
                new DOMPoint(right, y),
                new DOMPoint(right, bottom),
                new DOMPoint(x, bottom),
                0);
    }

    public static DOMQuad fromQuad() {
        return new DOMQuad();
    }

    /** Copies all four point dictionaries; null represents the empty dictionary. */
    public static DOMQuad fromQuad(DOMQuad other) {
        return other == null
                ? new DOMQuad()
                : fromQuad(other.p1, other.p2, other.p3, other.p4);
    }

    /** Compiler-facing DOMQuadInit conversion, with null members treated as empty dictionaries. */
    public static DOMQuad fromQuad(
            DOMPointReadOnly p1,
            DOMPointReadOnly p2,
            DOMPointReadOnly p3,
            DOMPointReadOnly p4) {
        return new DOMQuad(p1, p2, p3, p4);
    }

    /** Same-object point 1. Mutating it changes this quadrilateral. */
    public DOMPoint getP1() {
        return p1;
    }

    /** Same-object point 2. Mutating it changes this quadrilateral. */
    public DOMPoint getP2() {
        return p2;
    }

    /** Same-object point 3. Mutating it changes this quadrilateral. */
    public DOMPoint getP3() {
        return p3;
    }

    /** Same-object point 4. Mutating it changes this quadrilateral. */
    public DOMPoint getP4() {
        return p4;
    }

    /** Returns one new bounds rectangle; no coordinate array or collection is allocated. */
    public DOMRect getBounds() {
        double left = GeometryMath.minimum(p1.x, p2.x, p3.x, p4.x);
        double top = GeometryMath.minimum(p1.y, p2.y, p3.y, p4.y);
        double right = GeometryMath.maximum(p1.x, p2.x, p3.x, p4.x);
        double bottom = GeometryMath.maximum(p1.y, p2.y, p3.y, p4.y);
        return new DOMRect(left, top, right - left, bottom - top);
    }

    private static DOMPoint copyPoint(DOMPointReadOnly point) {
        return point == null
                ? new DOMPoint()
                : new DOMPoint(point.x, point.y, point.z, point.w);
    }
}
