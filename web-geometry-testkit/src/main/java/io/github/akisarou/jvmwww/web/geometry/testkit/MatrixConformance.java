package io.github.akisarou.jvmwww.web.geometry.testkit;

import io.github.akisarou.jvmwww.web.geometry.DOMMatrix;
import io.github.akisarou.jvmwww.web.geometry.DOMMatrixReadOnly;
import io.github.akisarou.jvmwww.web.geometry.DOMPoint;
import java.util.Random;

/** WPT-derived conformance and independent algebra traces for DOMMatrix. */
public final class MatrixConformance {
    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    private int passed;

    public static void main(String[] args) {
        new MatrixConformance().run();
    }

    private void run() {
        constructorsAndDimensionality();
        stickyDimensionality();
        transformPoint();
        twoDimensionalTransforms();
        multiplyAndPreMultiply();
        rotationsAndSkews();
        threeDimensionalRotationKernels();
        inversion();
        singularInversion();
        aliasing();
        randomizedProductsAndInverses();
        randomizedRotationKernels();
        System.out.println("DOMMatrix conformance: " + passed + " tests passed");
    }

    private void constructorsAndDimensionality() {
        DOMMatrixReadOnly identity = new DOMMatrixReadOnly();
        yes(identity.is2D(), "identity 2D");
        yes(identity.isIdentity(), "identity");

        DOMMatrixReadOnly affine = new DOMMatrixReadOnly(2, 3, 4, 5, 6, 7);
        eq(2, affine.getA(), "a");
        eq(3, affine.getB(), "b");
        eq(4, affine.getC(), "c");
        eq(5, affine.getD(), "d");
        eq(6, affine.getE(), "e");
        eq(7, affine.getF(), "f");
        yes(affine.is2D(), "six 2D");

        DOMMatrixReadOnly fullIdentity = new DOMMatrixReadOnly(new double[] {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        });
        no(fullIdentity.is2D(), "16 values always 3D");
        yes(fullIdentity.isIdentity(), "16 identity math");
        DOMMatrixReadOnly copy = new DOMMatrixReadOnly(fullIdentity);
        no(copy.is2D(), "copy flag");

        badLength(new double[0]);
        badLength(new double[5]);
        badLength(new double[15]);
        badLength(new double[17]);
        pass();
    }

    private void stickyDimensionality() {
        DOMMatrix matrix = new DOMMatrix();
        matrix.setM13(-0.0);
        yes(matrix.is2D(), "-0 preserves 2D");
        matrix.setM33(1.0);
        yes(matrix.is2D(), "one preserves 2D");
        matrix.setM13(2.0);
        no(matrix.is2D(), "3D write clears");
        matrix.setM13(0.0);
        no(matrix.is2D(), "sticky false");

        DOMMatrix originZ = new DOMMatrix().scaleSelf(1, 1, 1, 0, 0, 1);
        no(originZ.is2D(), "3D origin clears even identity result");
        pass();
    }

    private void transformPoint() {
        DOMMatrixReadOnly matrix = new DOMMatrixReadOnly(2, 0, 0, 3, 10, 20);
        DOMPoint point = matrix.transformPoint(new DOMPoint(4, 5, 6, 1));
        eq(18, point.getX(), "point x");
        eq(35, point.getY(), "point y");
        eq(6, point.getZ(), "point z");
        eq(1, point.getW(), "point w");

        DOMPoint transformed = new DOMPoint(4, 5, 6, 1).matrixTransform(matrix);
        eq(18, transformed.getX(), "matrixTransform x");
        DOMPoint identityCopy = new DOMPoint(1, 2, 3, 4).matrixTransform(null);
        eq(1, identityCopy.getX(), "null matrix identity");
        eq(4, identityCopy.getW(), "null matrix w");
        pass();
    }

    private void twoDimensionalTransforms() {
        DOMMatrix matrix = new DOMMatrix(1, 2, 3, 4, 5, 6);
        matrix.translateSelf(7, 8);
        eq(36, matrix.getE(), "translate e");
        eq(52, matrix.getF(), "translate f");

        matrix = new DOMMatrix(1, 2, 3, 4, 5, 6).scaleSelf(2, 3);
        eq(2, matrix.getA(), "scale a");
        eq(4, matrix.getB(), "scale b");
        eq(9, matrix.getC(), "scale c");
        eq(12, matrix.getD(), "scale d");
        eq(5, matrix.getE(), "scale e");

        matrix = new DOMMatrix().translateSelf(10, 20).scaleSelf(2, 3, 1, 4, 5, 0);
        DOMPoint point = matrix.transformPoint(new DOMPoint(4, 5));
        eq(14, point.getX(), "origin scale x");
        eq(25, point.getY(), "origin scale y");
        pass();
    }

    private void multiplyAndPreMultiply() {
        DOMMatrix left = new DOMMatrix(1, 2, 3, 4, 5, 6);
        DOMMatrix right = new DOMMatrix(7, 8, 9, 10, 11, 12);
        DOMMatrix product = new DOMMatrix(left).multiplySelf(right);
        matrix(multiply(values(left), values(right)), product, "multiply");

        DOMMatrix preProduct = new DOMMatrix(left).preMultiplySelf(right);
        matrix(multiply(values(right), values(left)), preProduct, "premultiply");
        pass();
    }

    private void rotationsAndSkews() {
        DOMPoint rotated = new DOMMatrix().rotateSelf(90).transformPoint(new DOMPoint(1, 0));
        near(0, rotated.getX(), 1e-12, "rotate x");
        near(1, rotated.getY(), 1e-12, "rotate y");

        DOMMatrix rotation3d = new DOMMatrix().rotateSelf(90, 90, 90);
        no(rotation3d.is2D(), "3d rotate flag");

        DOMMatrix folded = new DOMMatrix().rotateSelf(180, 180, 90);
        near(0, folded.getA(), 1e-12, "folded rotation a");
        near(-1, folded.getB(), 1e-12, "folded rotation b");
        near(1, folded.getC(), 1e-12, "folded rotation c");
        near(0, folded.getD(), 1e-12, "folded rotation d");
        no(folded.is2D(), "folded rotation stays observably 3D");

        DOMMatrix axis = new DOMMatrix().rotateAxisAngleSelf(0, 0, 3, 90);
        DOMPoint axisPoint = axis.transformPoint(new DOMPoint(1, 0));
        near(0, axisPoint.getX(), 1e-12, "axis x");
        near(1, axisPoint.getY(), 1e-12, "axis y");
        yes(axis.is2D(), "z axis 2d");

        DOMMatrix skewX = new DOMMatrix().skewXSelf(45);
        near(1, skewX.getC(), 1e-12, "skew x");
        DOMMatrix skewY = new DOMMatrix().skewYSelf(45);
        near(1, skewY.getB(), 1e-12, "skew y");
        pass();
    }

    /** Exercises the specialized 3x3 post-multiply path on non-identity source matrices. */
    private void threeDimensionalRotationKernels() {
        double[] source = {
            2, 3, 5, 7,
            11, 13, 17, 19,
            23, 29, 31, 37,
            41, 43, 47, 53
        };

        double[] axisRotation = axisRotation(2, -3, 4, 37);
        DOMMatrix axisResult = matrix(source).rotateAxisAngleSelf(2, -3, 4, 37);
        matrix(multiply(source, axisRotation), axisResult, 5e-12, "non-identity axis rotation");
        no(axisResult.is2D(), "mixed axis is 3D");

        double rotateX = 13;
        double rotateY = -27;
        double rotateZ = 41;
        double[] expectedOrdered = multiply(source, axisRotation(0, 0, 1, rotateZ));
        expectedOrdered = multiply(expectedOrdered, axisRotation(0, 1, 0, rotateY));
        expectedOrdered = multiply(expectedOrdered, axisRotation(1, 0, 0, rotateX));
        DOMMatrix ordered = matrix(source).rotateSelf(rotateX, rotateY, rotateZ);
        matrix(expectedOrdered, ordered, 5e-12, "Z-Y-X rotation order");

        DOMMatrix affine = new DOMMatrix(2, 3, 5, 7, 11, 13);
        double[] affineBefore = values(affine);
        DOMMatrix zAxis = new DOMMatrix(affine).rotateAxisAngleSelf(-0.0, 0.0, 5.0, 23.0);
        matrix(
                multiply(affineBefore, axisRotation(-0.0, 0.0, 5.0, 23.0)),
                zAxis,
                5e-12,
                "z-axis affine rotation");
        yes(zAxis.is2D(), "z-axis rotation preserves 2D");

        DOMMatrix zeroAxis = new DOMMatrix(affine);
        zeroAxis.rotateAxisAngleSelf(0.0, 0.0, 0.0, 91.0);
        matrix(affineBefore, zeroAxis, "zero axis no-op");
        yes(zeroAxis.is2D(), "zero axis preserves dimensionality");
        pass();
    }

    private void inversion() {
        DOMMatrix matrix = new DOMMatrix(2, 1, 3, 4, 5, 6);
        DOMMatrix inverse = new DOMMatrix(matrix).invertSelf();
        yes(inverse.is2D(), "2d inverse remains 2d");
        matrixIdentity(matrix.multiply(inverse), 1e-12, "2d inverse product");

        DOMMatrix full = new DOMMatrix(new double[] {
            2, 1, 0, 0,
            1, 3, 0, 0,
            0, 0, 4, 0,
            5, 6, 7, 1
        });
        DOMMatrix fullInverse = new DOMMatrix(full).invertSelf();
        no(fullInverse.is2D(), "3d inverse flag");
        matrixIdentity(full.multiply(fullInverse), 1e-11, "4d inverse product");
        pass();
    }

    private void singularInversion() {
        DOMMatrix matrix = new DOMMatrix();
        matrix.setA(0);
        matrix.invertSelf();
        no(matrix.is2D(), "singular clears 2D");
        no(matrix.isIdentity(), "singular not identity");
        for (double value : values(matrix)) {
            yes(Double.isNaN(value), "singular NaN");
        }
        pass();
    }

    private void aliasing() {
        DOMMatrix matrix = new DOMMatrix(1, 2, 3, 4, 5, 6);
        double[] expected = multiply(values(matrix), values(matrix));
        matrix.multiplySelf(matrix);
        matrix(expected, matrix, "multiply alias");

        DOMMatrix preMatrix = new DOMMatrix(1, 2, 3, 4, 5, 6);
        expected = multiply(values(preMatrix), values(preMatrix));
        preMatrix.preMultiplySelf(preMatrix);
        matrix(expected, preMatrix, "premultiply alias");
        pass();
    }

    private void randomizedProductsAndInverses() {
        Random random = new Random(0x5eed1234L);
        for (int index = 0; index < 10000; index++) {
            double[] leftValues = randomMatrix(random);
            double[] rightValues = randomMatrix(random);
            DOMMatrix left = matrix(leftValues);
            DOMMatrix right = matrix(rightValues);
            matrix(
                    multiply(leftValues, rightValues),
                    new DOMMatrix(left).multiplySelf(right),
                    "random mul " + index);
            matrix(
                    multiply(rightValues, leftValues),
                    new DOMMatrix(left).preMultiplySelf(right),
                    "random pre " + index);
            DOMMatrix inverse = new DOMMatrix(left).invertSelf();
            if (!Double.isNaN(inverse.getM11())) {
                matrixIdentity(left.multiply(inverse), 2e-8, "random inverse " + index);
            }
        }
        pass();
    }

    /** Falsifies coefficient drift in the optimized rotation path against generic multiplication. */
    private void randomizedRotationKernels() {
        Random random = new Random(0x3d5eed42L);
        for (int index = 0; index < 10000; index++) {
            double[] source = randomMatrix(random);
            double axisX = randomSigned(random, 3.0);
            double axisY = randomSigned(random, 3.0);
            double axisZ = randomSigned(random, 3.0);
            if (axisX == 0.0 && axisY == 0.0 && axisZ == 0.0) {
                axisZ = 1.0;
            }
            double angle = randomSigned(random, 720.0);
            DOMMatrix actual = matrix(source).rotateAxisAngleSelf(axisX, axisY, axisZ, angle);
            matrix(
                    multiply(source, axisRotation(axisX, axisY, axisZ, angle)),
                    actual,
                    8e-12,
                    "random axis rotation " + index);

            double rotateX = randomSigned(random, 360.0);
            double rotateY = randomSigned(random, 360.0);
            double rotateZ = randomSigned(random, 360.0);
            double[] expected = multiply(source, axisRotation(0.0, 0.0, 1.0, rotateZ));
            expected = multiply(expected, axisRotation(0.0, 1.0, 0.0, rotateY));
            expected = multiply(expected, axisRotation(1.0, 0.0, 0.0, rotateX));
            matrix(
                    expected,
                    matrix(source).rotateSelf(rotateX, rotateY, rotateZ),
                    1e-11,
                    "random ordered rotation " + index);
        }
        pass();
    }

    private static void badLength(double[] values) {
        try {
            new DOMMatrixReadOnly(values);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid sequence length accepted: " + values.length);
    }

    private static double randomSigned(Random random, double magnitude) {
        return (random.nextDouble() * 2.0 - 1.0) * magnitude;
    }

    private static double[] randomMatrix(Random random) {
        double[] values = new double[16];
        for (int index = 0; index < 16; index++) {
            values[index] = randomSigned(random, 2.0);
        }
        values[0] += 4;
        values[5] += 4;
        values[10] += 4;
        values[15] += 4;
        return values;
    }

    /** Independent Rodrigues-form rotation matrix in Geometry Interfaces column-major order. */
    private static double[] axisRotation(double x, double y, double z, double angleDegrees) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0.0) {
            return new double[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
            };
        }
        double nx = x / length;
        double ny = y / length;
        double nz = z / length;
        double angle = angleDegrees * DEGREES_TO_RADIANS;
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        double oneMinusCosine = 1.0 - cosine;
        return new double[] {
            oneMinusCosine * nx * nx + cosine,
            oneMinusCosine * nx * ny + sine * nz,
            oneMinusCosine * nx * nz - sine * ny,
            0,
            oneMinusCosine * nx * ny - sine * nz,
            oneMinusCosine * ny * ny + cosine,
            oneMinusCosine * ny * nz + sine * nx,
            0,
            oneMinusCosine * nx * nz + sine * ny,
            oneMinusCosine * ny * nz - sine * nx,
            oneMinusCosine * nz * nz + cosine,
            0,
            0, 0, 0, 1
        };
    }

    private static DOMMatrix matrix(double[] values) {
        return new DOMMatrix(values);
    }

    private static double[] values(DOMMatrixReadOnly matrix) {
        return new double[] {
            matrix.getM11(), matrix.getM12(), matrix.getM13(), matrix.getM14(),
            matrix.getM21(), matrix.getM22(), matrix.getM23(), matrix.getM24(),
            matrix.getM31(), matrix.getM32(), matrix.getM33(), matrix.getM34(),
            matrix.getM41(), matrix.getM42(), matrix.getM43(), matrix.getM44()
        };
    }

    private static double[] multiply(double[] left, double[] right) {
        double[] product = new double[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0.0;
                for (int inner = 0; inner < 4; inner++) {
                    value += left[inner * 4 + row] * right[column * 4 + inner];
                }
                product[column * 4 + row] = value;
            }
        }
        return product;
    }

    private static void matrix(double[] expected, DOMMatrixReadOnly actual, String label) {
        matrix(expected, actual, 1e-12, label);
    }

    private static void matrix(
            double[] expected,
            DOMMatrixReadOnly actual,
            double epsilon,
            String label) {
        double[] actualValues = values(actual);
        for (int index = 0; index < 16; index++) {
            near(expected[index], actualValues[index], epsilon, label + "[" + index + "]");
        }
    }

    private static void matrixIdentity(DOMMatrixReadOnly matrix, double epsilon, String label) {
        double[] values = values(matrix);
        for (int index = 0; index < 16; index++) {
            near(index % 5 == 0 ? 1.0 : 0.0, values[index], epsilon, label + "[" + index + "]");
        }
    }

    private void pass() {
        passed++;
    }

    private static void yes(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void no(boolean condition, String label) {
        yes(!condition, label);
    }

    private static void eq(double expected, double actual, String label) {
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void near(double expected, double actual, double epsilon, String label) {
        boolean different = Double.isNaN(expected)
                ? !Double.isNaN(actual)
                : Math.abs(expected - actual)
                        > epsilon * Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
        if (different) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
