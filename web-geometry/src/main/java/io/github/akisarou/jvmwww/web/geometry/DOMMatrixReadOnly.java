package io.github.akisarou.jvmwww.web.geometry;

/** Primitive-backed read-only DOMMatrix for the selected Geometry Interfaces profile. */
public class DOMMatrixReadOnly {
    double m11; double m12; double m13; double m14;
    double m21; double m22; double m23; double m24;
    double m31; double m32; double m33; double m34;
    double m41; double m42; double m43; double m44;
    boolean is2D;

    public DOMMatrixReadOnly() {
        setIdentity(true);
    }

    /** Copies matrix values and the observable is2D flag. Null represents an empty dictionary. */
    public DOMMatrixReadOnly(DOMMatrixReadOnly other) {
        if (other == null) {
            setIdentity(true);
        } else {
            copyFrom(other);
        }
    }

    /** Six-value 2D sequence in [a,b,c,d,e,f] order. */
    public DOMMatrixReadOnly(
            double a, double b, double c, double d, double e, double f) {
        setIdentity(true);
        m11 = a; m12 = b; m21 = c; m22 = d; m41 = e; m42 = f;
    }

    /** Six- or sixteen-value sequence. Six values create a 2D matrix; sixteen create a 3D matrix. */
    public DOMMatrixReadOnly(double[] values) {
        if (values == null) {
            setIdentity(true);
            return;
        }
        if (values.length == 6) {
            setIdentity(true);
            m11 = values[0]; m12 = values[1]; m21 = values[2];
            m22 = values[3]; m41 = values[4]; m42 = values[5];
            return;
        }
        if (values.length == 16) {
            setValues(
                    values[0], values[1], values[2], values[3],
                    values[4], values[5], values[6], values[7],
                    values[8], values[9], values[10], values[11],
                    values[12], values[13], values[14], values[15],
                    false);
            return;
        }
        throw new IllegalArgumentException("DOMMatrix sequence must contain 6 or 16 values");
    }

    /** Primitive sixteen-value sequence in column-major Geometry Interfaces order. */
    public DOMMatrixReadOnly(
            double m11, double m12, double m13, double m14,
            double m21, double m22, double m23, double m24,
            double m31, double m32, double m33, double m34,
            double m41, double m42, double m43, double m44) {
        setValues(
                m11, m12, m13, m14,
                m21, m22, m23, m24,
                m31, m32, m33, m34,
                m41, m42, m43, m44,
                false);
    }

    public static DOMMatrixReadOnly fromMatrix() { return new DOMMatrixReadOnly(); }
    public static DOMMatrixReadOnly fromMatrix(DOMMatrixReadOnly other) {
        return new DOMMatrixReadOnly(other);
    }

    public final double getA() { return m11; }
    public final double getB() { return m12; }
    public final double getC() { return m21; }
    public final double getD() { return m22; }
    public final double getE() { return m41; }
    public final double getF() { return m42; }

    public final double getM11() { return m11; }
    public final double getM12() { return m12; }
    public final double getM13() { return m13; }
    public final double getM14() { return m14; }
    public final double getM21() { return m21; }
    public final double getM22() { return m22; }
    public final double getM23() { return m23; }
    public final double getM24() { return m24; }
    public final double getM31() { return m31; }
    public final double getM32() { return m32; }
    public final double getM33() { return m33; }
    public final double getM34() { return m34; }
    public final double getM41() { return m41; }
    public final double getM42() { return m42; }
    public final double getM43() { return m43; }
    public final double getM44() { return m44; }
    public final boolean is2D() { return is2D; }

    public final boolean isIdentity() {
        return m11 == 1.0 && m12 == 0.0 && m13 == 0.0 && m14 == 0.0
                && m21 == 0.0 && m22 == 1.0 && m23 == 0.0 && m24 == 0.0
                && m31 == 0.0 && m32 == 0.0 && m33 == 1.0 && m34 == 0.0
                && m41 == 0.0 && m42 == 0.0 && m43 == 0.0 && m44 == 1.0;
    }

    public DOMMatrix multiply(DOMMatrixReadOnly other) {
        return other == null ? new DOMMatrix(this) : new DOMMatrix(this).multiplySelf(other);
    }

    public DOMMatrix translate(double tx) { return translate(tx, 0.0, 0.0); }
    public DOMMatrix translate(double tx, double ty) { return translate(tx, ty, 0.0); }
    public DOMMatrix translate(double tx, double ty, double tz) {
        return new DOMMatrix(this).translateSelf(tx, ty, tz);
    }

    public DOMMatrix scale(double scale) { return scale(scale, scale, 1.0, 0.0, 0.0, 0.0); }
    public DOMMatrix scale(double sx, double sy) { return scale(sx, sy, 1.0, 0.0, 0.0, 0.0); }
    public DOMMatrix scale(double sx, double sy, double sz) {
        return scale(sx, sy, sz, 0.0, 0.0, 0.0);
    }
    public DOMMatrix scale(double sx, double sy, double sz, double ox, double oy, double oz) {
        return new DOMMatrix(this).scaleSelf(sx, sy, sz, ox, oy, oz);
    }

    public DOMMatrix rotate(double rotZ) { return new DOMMatrix(this).rotateSelf(rotZ); }
    public DOMMatrix rotate(double rotX, double rotY, double rotZ) {
        return new DOMMatrix(this).rotateSelf(rotX, rotY, rotZ);
    }
    public DOMMatrix rotateFromVector(double x, double y) {
        return new DOMMatrix(this).rotateFromVectorSelf(x, y);
    }
    public DOMMatrix rotateAxisAngle(double x, double y, double z, double angle) {
        return new DOMMatrix(this).rotateAxisAngleSelf(x, y, z, angle);
    }
    public DOMMatrix skewX(double angle) { return new DOMMatrix(this).skewXSelf(angle); }
    public DOMMatrix skewY(double angle) { return new DOMMatrix(this).skewYSelf(angle); }
    public DOMMatrix flipX() { return new DOMMatrix(this).scaleSelf(-1.0, 1.0, 1.0); }
    public DOMMatrix flipY() { return new DOMMatrix(this).scaleSelf(1.0, -1.0, 1.0); }
    public DOMMatrix inverse() { return new DOMMatrix(this).invertSelf(); }

    /** Returns one new point; no coordinate array or intermediate matrix is allocated. */
    public DOMPoint transformPoint(DOMPointReadOnly point) {
        double x = point == null ? 0.0 : point.x;
        double y = point == null ? 0.0 : point.y;
        double z = point == null ? 0.0 : point.z;
        double w = point == null ? 1.0 : point.w;
        return new DOMPoint(
                x * m11 + y * m21 + z * m31 + w * m41,
                x * m12 + y * m22 + z * m32 + w * m42,
                x * m13 + y * m23 + z * m33 + w * m43,
                x * m14 + y * m24 + z * m34 + w * m44);
    }

    final void copyFrom(DOMMatrixReadOnly other) {
        setValues(
                other.m11, other.m12, other.m13, other.m14,
                other.m21, other.m22, other.m23, other.m24,
                other.m31, other.m32, other.m33, other.m34,
                other.m41, other.m42, other.m43, other.m44,
                other.is2D);
    }

    final void setIdentity(boolean valueIs2D) {
        setValues(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
                valueIs2D);
    }

    final void setValues(
            double nm11, double nm12, double nm13, double nm14,
            double nm21, double nm22, double nm23, double nm24,
            double nm31, double nm32, double nm33, double nm34,
            double nm41, double nm42, double nm43, double nm44,
            boolean valueIs2D) {
        m11 = nm11; m12 = nm12; m13 = nm13; m14 = nm14;
        m21 = nm21; m22 = nm22; m23 = nm23; m24 = nm24;
        m31 = nm31; m32 = nm32; m33 = nm33; m34 = nm34;
        m41 = nm41; m42 = nm42; m43 = nm43; m44 = nm44;
        is2D = valueIs2D;
    }
}
