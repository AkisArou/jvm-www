package io.github.akisarou.jvmwww.web.geometry;

/** Mutable primitive-backed DOMMatrix with allocation-free transform kernels. */
public final class DOMMatrix extends DOMMatrixReadOnly {
    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    public DOMMatrix() { super(); }
    public DOMMatrix(DOMMatrixReadOnly other) { super(other); }
    public DOMMatrix(double a, double b, double c, double d, double e, double f) {
        super(a, b, c, d, e, f);
    }
    public DOMMatrix(double[] values) { super(values); }
    public DOMMatrix(
            double m11, double m12, double m13, double m14,
            double m21, double m22, double m23, double m24,
            double m31, double m32, double m33, double m34,
            double m41, double m42, double m43, double m44) {
        super(
                m11, m12, m13, m14,
                m21, m22, m23, m24,
                m31, m32, m33, m34,
                m41, m42, m43, m44);
    }

    public static DOMMatrix fromMatrix() { return new DOMMatrix(); }
    public static DOMMatrix fromMatrix(DOMMatrixReadOnly other) { return new DOMMatrix(other); }

    public void setA(double value) { m11 = value; }
    public void setB(double value) { m12 = value; }
    public void setC(double value) { m21 = value; }
    public void setD(double value) { m22 = value; }
    public void setE(double value) { m41 = value; }
    public void setF(double value) { m42 = value; }

    public void setM11(double value) { m11 = value; }
    public void setM12(double value) { m12 = value; }
    public void setM13(double value) { m13 = value; clear2DUnlessZero(value); }
    public void setM14(double value) { m14 = value; clear2DUnlessZero(value); }
    public void setM21(double value) { m21 = value; }
    public void setM22(double value) { m22 = value; }
    public void setM23(double value) { m23 = value; clear2DUnlessZero(value); }
    public void setM24(double value) { m24 = value; clear2DUnlessZero(value); }
    public void setM31(double value) { m31 = value; clear2DUnlessZero(value); }
    public void setM32(double value) { m32 = value; clear2DUnlessZero(value); }
    public void setM33(double value) { m33 = value; clear2DUnlessOne(value); }
    public void setM34(double value) { m34 = value; clear2DUnlessZero(value); }
    public void setM41(double value) { m41 = value; }
    public void setM42(double value) { m42 = value; }
    public void setM43(double value) { m43 = value; clear2DUnlessZero(value); }
    public void setM44(double value) { m44 = value; clear2DUnlessOne(value); }

    public DOMMatrix multiplySelf(DOMMatrixReadOnly other) {
        if (other == null) return this;
        DOMMatrixReadOnly right = other;
        if (is2D && right.is2D) {
            double a1 = m11, b1 = m12, c1 = m21, d1 = m22, e1 = m41, f1 = m42;
            double a2 = right.m11, b2 = right.m12, c2 = right.m21, d2 = right.m22;
            double e2 = right.m41, f2 = right.m42;
            m11 = a1 * a2 + c1 * b2;
            m12 = b1 * a2 + d1 * b2;
            m21 = a1 * c2 + c1 * d2;
            m22 = b1 * c2 + d1 * d2;
            m41 = a1 * e2 + c1 * f2 + e1;
            m42 = b1 * e2 + d1 * f2 + f1;
            return this;
        }
        multiplyFull(this, right, false);
        return this;
    }

    public DOMMatrix preMultiplySelf(DOMMatrixReadOnly other) {
        if (other == null) return this;
        DOMMatrixReadOnly left = other;
        if (is2D && left.is2D) {
            double a1 = left.m11, b1 = left.m12, c1 = left.m21, d1 = left.m22;
            double e1 = left.m41, f1 = left.m42;
            double a2 = m11, b2 = m12, c2 = m21, d2 = m22, e2 = m41, f2 = m42;
            m11 = a1 * a2 + c1 * b2;
            m12 = b1 * a2 + d1 * b2;
            m21 = a1 * c2 + c1 * d2;
            m22 = b1 * c2 + d1 * d2;
            m41 = a1 * e2 + c1 * f2 + e1;
            m42 = b1 * e2 + d1 * f2 + f1;
            return this;
        }
        multiplyFull(left, this, false);
        return this;
    }

    public DOMMatrix translateSelf(double tx) { return translateSelf(tx, 0.0, 0.0); }
    public DOMMatrix translateSelf(double tx, double ty) { return translateSelf(tx, ty, 0.0); }
    public DOMMatrix translateSelf(double tx, double ty, double tz) {
        if (tz != 0.0) is2D = false;
        double n41 = m11 * tx + m21 * ty + m31 * tz + m41;
        double n42 = m12 * tx + m22 * ty + m32 * tz + m42;
        double n43 = m13 * tx + m23 * ty + m33 * tz + m43;
        double n44 = m14 * tx + m24 * ty + m34 * tz + m44;
        m41 = n41; m42 = n42; m43 = n43; m44 = n44;
        return this;
    }

    public DOMMatrix scaleSelf(double scale) { return scaleSelf(scale, scale, 1.0); }
    public DOMMatrix scaleSelf(double sx, double sy) { return scaleSelf(sx, sy, 1.0); }
    public DOMMatrix scaleSelf(double sx, double sy, double sz) {
        return scaleSelf(sx, sy, sz, 0.0, 0.0, 0.0);
    }
    public DOMMatrix scaleSelf(double sx, double sy, double sz, double ox, double oy, double oz) {
        translateSelf(ox, oy, oz);
        m11 *= sx; m12 *= sx; m13 *= sx; m14 *= sx;
        m21 *= sy; m22 *= sy; m23 *= sy; m24 *= sy;
        m31 *= sz; m32 *= sz; m33 *= sz; m34 *= sz;
        if (sz != 1.0 || oz != 0.0) is2D = false;
        translateSelf(-ox, -oy, -oz);
        return this;
    }

    public DOMMatrix rotateSelf(double rotZ) { return rotateAxisAngleSelf(0.0, 0.0, 1.0, rotZ); }

    /** Applies rotations in specification order: Z, then Y, then X. */
    public DOMMatrix rotateSelf(double rotX, double rotY, double rotZ) {
        rotateAxisAngleSelf(0.0, 0.0, 1.0, rotZ);
        rotateAxisAngleSelf(0.0, 1.0, 0.0, rotY);
        rotateAxisAngleSelf(1.0, 0.0, 0.0, rotX);
        return this;
    }

    public DOMMatrix rotateFromVectorSelf(double x, double y) {
        double angle = x == 0.0 && y == 0.0 ? 0.0 : Math.atan2(y, x) / DEGREES_TO_RADIANS;
        return rotateSelf(angle);
    }

    public DOMMatrix rotateAxisAngleSelf(double x, double y, double z, double angle) {
        double length = Math.hypot(Math.hypot(x, y), z);
        if (length == 0.0) return this;
        double nx = x / length, ny = y / length, nz = z / length;
        if (nx != 0.0 || ny != 0.0) is2D = false;

        double half = angle * DEGREES_TO_RADIANS * 0.5;
        double sin = Math.sin(half);
        double cos = Math.cos(half);
        double sc = sin * cos;
        double sq = sin * sin;
        double r11 = 1.0 - 2.0 * (ny * ny + nz * nz) * sq;
        double r12 = 2.0 * (nx * ny * sq + nz * sc);
        double r13 = 2.0 * (nx * nz * sq - ny * sc);
        double r21 = 2.0 * (nx * ny * sq - nz * sc);
        double r22 = 1.0 - 2.0 * (nx * nx + nz * nz) * sq;
        double r23 = 2.0 * (ny * nz * sq + nx * sc);
        double r31 = 2.0 * (nx * nz * sq + ny * sc);
        double r32 = 2.0 * (ny * nz * sq - nx * sc);
        double r33 = 1.0 - 2.0 * (nx * nx + ny * ny) * sq;
        postMultiply3x3(r11, r12, r13, r21, r22, r23, r31, r32, r33);
        return this;
    }

    public DOMMatrix skewXSelf(double angle) {
        double t = Math.tan(angle * DEGREES_TO_RADIANS);
        m21 += m11 * t; m22 += m12 * t; m23 += m13 * t; m24 += m14 * t;
        return this;
    }

    public DOMMatrix skewYSelf(double angle) {
        double t = Math.tan(angle * DEGREES_TO_RADIANS);
        m11 += m21 * t; m12 += m22 * t; m13 += m23 * t; m14 += m24 * t;
        return this;
    }

    public DOMMatrix invertSelf() {
        if (is2D) return invert2D();
        return invert4D();
    }

    private DOMMatrix invert2D() {
        double a = m11, b = m12, c = m21, d = m22, e = m41, f = m42;
        double determinant = a * d - b * c;
        if (determinant == 0.0 || Double.isNaN(determinant)) return setAllNaN();
        double inverseDeterminant = 1.0 / determinant;
        m11 = d * inverseDeterminant;
        m12 = -b * inverseDeterminant;
        m21 = -c * inverseDeterminant;
        m22 = a * inverseDeterminant;
        m41 = (c * f - d * e) * inverseDeterminant;
        m42 = (b * e - a * f) * inverseDeterminant;
        return this;
    }

    /** Allocation-free adjugate inversion of the full column-major 4x4 matrix. */
    private DOMMatrix invert4D() {
        double a00=m11,a01=m12,a02=m13,a03=m14;
        double a10=m21,a11=m22,a12=m23,a13=m24;
        double a20=m31,a21=m32,a22=m33,a23=m34;
        double a30=m41,a31=m42,a32=m43,a33=m44;

        double b00=a00*a11-a01*a10;
        double b01=a00*a12-a02*a10;
        double b02=a00*a13-a03*a10;
        double b03=a01*a12-a02*a11;
        double b04=a01*a13-a03*a11;
        double b05=a02*a13-a03*a12;
        double b06=a20*a31-a21*a30;
        double b07=a20*a32-a22*a30;
        double b08=a20*a33-a23*a30;
        double b09=a21*a32-a22*a31;
        double b10=a21*a33-a23*a31;
        double b11=a22*a33-a23*a32;
        double determinant=b00*b11-b01*b10+b02*b09+b03*b08-b04*b07+b05*b06;
        if (determinant == 0.0 || Double.isNaN(determinant)) return setAllNaN();
        double id=1.0/determinant;

        setValues(
                (a11*b11-a12*b10+a13*b09)*id,
                (a02*b10-a01*b11-a03*b09)*id,
                (a31*b05-a32*b04+a33*b03)*id,
                (a22*b04-a21*b05-a23*b03)*id,
                (a12*b08-a10*b11-a13*b07)*id,
                (a00*b11-a02*b08+a03*b07)*id,
                (a32*b02-a30*b05-a33*b01)*id,
                (a20*b05-a22*b02+a23*b01)*id,
                (a10*b10-a11*b08+a13*b06)*id,
                (a01*b08-a00*b10-a03*b06)*id,
                (a30*b04-a31*b02+a33*b00)*id,
                (a21*b02-a20*b04-a23*b00)*id,
                (a11*b07-a10*b09-a12*b06)*id,
                (a00*b09-a01*b07+a02*b06)*id,
                (a31*b01-a30*b03-a32*b00)*id,
                (a20*b03-a21*b01+a22*b00)*id,
                false);
        return this;
    }

    private DOMMatrix setAllNaN() {
        double n = Double.NaN;
        setValues(n,n,n,n,n,n,n,n,n,n,n,n,n,n,n,n,false);
        return this;
    }

    private void postMultiply3x3(
            double r11,double r12,double r13,
            double r21,double r22,double r23,
            double r31,double r32,double r33) {
        double a11=m11,a12=m12,a13=m13,a14=m14;
        double a21=m21,a22=m22,a23=m23,a24=m24;
        double a31=m31,a32=m32,a33=m33,a34=m34;
        m11=a11*r11+a21*r12+a31*r13;
        m12=a12*r11+a22*r12+a32*r13;
        m13=a13*r11+a23*r12+a33*r13;
        m14=a14*r11+a24*r12+a34*r13;
        m21=a11*r21+a21*r22+a31*r23;
        m22=a12*r21+a22*r22+a32*r23;
        m23=a13*r21+a23*r22+a33*r23;
        m24=a14*r21+a24*r22+a34*r23;
        m31=a11*r31+a21*r32+a31*r33;
        m32=a12*r31+a23*r32+a32*r33;
        m33=a13*r31+a23*r32+a33*r33;
        m34=a14*r31+a24*r32+a34*r33;
    }

    private void multiplyFull(DOMMatrixReadOnly left, DOMMatrixReadOnly right, boolean ignored) {
        double l11=left.m11,l12=left.m12,l13=left.m13,l14=left.m14;
        double l21=left.m21,l22=left.m22,l23=left.m23,l24=left.m24;
        double l31=left.m31,l32=left.m32,l33=left.m33,l34=left.m34;
        double l41=left.m41,l42=left.m42,l43=left.m43,l44=left.m44;
        double r11=right.m11,r12=right.m12,r13=right.m13,r14=right.m14;
        double r21=right.m21,r22=right.m22,r23=right.m23,r24=right.m24;
        double r31=right.m31,r32=right.m32,r33=right.m33,r34=right.m34;
        double r41=right.m41,r42=right.m42,r43=right.m43,r44=right.m44;
        setValues(
                l11*r11+l21*r12+l31*r13+l41*r14,
                l12*r11+l22*r12+l32*r13+l42*r14,
                l13*r11+l23*r12+l33*r13+l43*r14,
                l14*r11+l24*r12+l34*r13+l44*r14,
                l11*r21+l21*r22+l31*r23+l41*r24,
                l12*r21+l22*r22+l32*r23+l42*r24,
                l13*r21+l23*r22+l33*r23+l43*r24,
                l14*r21+l24*r22+l34*r23+l44*r24,
                l11*r31+l21*r32+l31*r33+l41*r34,
                l12*r31+l22*r32+l32*r33+l42*r34,
                l13*r31+l23*r32+l33*r33+l43*r34,
                l14*r31+l24*r32+l34*r33+l44*r34,
                l11*r41+l21*r42+l31*r43+l41*r44,
                l12*r41+l22*r42+l32*r43+l42*r44,
                l13*r41+l23*r42+l33*r43+l43*r44,
                l14*r41+l24*r42+l34*r43+l44*r44,
                left.is2D && right.is2D);
    }

    private void clear2DUnlessZero(double value) { if (value != 0.0) is2D = false; }
    private void clear2DUnlessOne(double value) { if (value != 1.0) is2D = false; }
}
