package io.github.akisarou.jvmwww.web.geometry.testkit;

import io.github.akisarou.jvmwww.web.geometry.DOMPoint;
import io.github.akisarou.jvmwww.web.geometry.DOMPointReadOnly;
import io.github.akisarou.jvmwww.web.geometry.DOMQuad;
import io.github.akisarou.jvmwww.web.geometry.DOMRect;
import io.github.akisarou.jvmwww.web.geometry.DOMRectReadOnly;

/** Deterministic Java 8 conformance for the selected Geometry Interfaces value profile. */
public final class GeometryConformance {
    private int passed;

    public static void main(String[] args) {
        new GeometryConformance().run();
    }

    private void run() {
        pointDefaultsAndCopies();
        pointUnrestrictedDoublesAndMutation();
        rectangleEdgesAndNegativeDimensions();
        rectangleMutationAndCopies();
        rectangleNaNAndSignedZero();
        quadCopiesAndSameObjectPoints();
        quadFromRectAndLiveBounds();
        quadNaNSafeBounds();
        System.out.println("Geometry conformance: " + passed + " tests passed");
    }

    private void pointDefaultsAndCopies() {
        DOMPointReadOnly defaults = new DOMPointReadOnly();
        eq(0.0, defaults.getX(), "default x");
        eq(0.0, defaults.getY(), "default y");
        eq(0.0, defaults.getZ(), "default z");
        eq(1.0, defaults.getW(), "default w");

        DOMPointReadOnly partial = new DOMPointReadOnly(2.0, 3.0);
        eq(2.0, partial.getX(), "partial x");
        eq(3.0, partial.getY(), "partial y");
        eq(0.0, partial.getZ(), "partial z default");
        eq(1.0, partial.getW(), "partial w default");

        DOMPointReadOnly copy = DOMPointReadOnly.fromPoint(partial);
        notSame(partial, copy, "read-only point copy identity");
        eq(2.0, copy.getX(), "read-only point copy x");
        eq(3.0, copy.getY(), "read-only point copy y");

        DOMPointReadOnly empty = DOMPointReadOnly.fromPoint((DOMPointReadOnly) null);
        eq(0.0, empty.getX(), "null dictionary x");
        eq(1.0, empty.getW(), "null dictionary w");
        pass();
    }

    private void pointUnrestrictedDoublesAndMutation() {
        DOMPoint point = new DOMPoint(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                -0.0);
        yes(Double.isNaN(point.getX()), "point NaN preserved");
        eq(Double.POSITIVE_INFINITY, point.getY(), "point positive infinity");
        eq(Double.NEGATIVE_INFINITY, point.getZ(), "point negative infinity");
        raw(-0.0, point.getW(), "point signed zero");

        DOMPoint mutableCopy = DOMPoint.fromPoint(point);
        notSame(point, mutableCopy, "mutable point copy identity");
        mutableCopy.setX(7.0);
        mutableCopy.setY(8.0);
        mutableCopy.setZ(9.0);
        mutableCopy.setW(10.0);
        eq(7.0, mutableCopy.getX(), "mutable x");
        eq(8.0, mutableCopy.getY(), "mutable y");
        eq(9.0, mutableCopy.getZ(), "mutable z");
        eq(10.0, mutableCopy.getW(), "mutable w");
        yes(Double.isNaN(point.getX()), "source point remains independent");
        pass();
    }

    private void rectangleEdgesAndNegativeDimensions() {
        DOMRectReadOnly positive = new DOMRectReadOnly(10.0, 20.0, 30.0, 40.0);
        eq(10.0, positive.getLeft(), "positive left");
        eq(40.0, positive.getRight(), "positive right");
        eq(20.0, positive.getTop(), "positive top");
        eq(60.0, positive.getBottom(), "positive bottom");

        DOMRectReadOnly negative = new DOMRectReadOnly(10.0, 20.0, -30.0, -40.0);
        eq(-20.0, negative.getLeft(), "negative-width left");
        eq(10.0, negative.getRight(), "negative-width right");
        eq(-20.0, negative.getTop(), "negative-height top");
        eq(20.0, negative.getBottom(), "negative-height bottom");
        eq(-30.0, negative.getWidth(), "negative width retained");
        eq(-40.0, negative.getHeight(), "negative height retained");
        pass();
    }

    private void rectangleMutationAndCopies() {
        DOMRect source = new DOMRect(1.0, 2.0, 3.0, 4.0);
        DOMRect copy = DOMRect.fromRect(source);
        notSame(source, copy, "rectangle copy identity");

        source.setX(10.0);
        source.setY(20.0);
        source.setWidth(-5.0);
        source.setHeight(-8.0);
        eq(5.0, source.getLeft(), "mutated left");
        eq(10.0, source.getRight(), "mutated right");
        eq(12.0, source.getTop(), "mutated top");
        eq(20.0, source.getBottom(), "mutated bottom");

        eq(1.0, copy.getX(), "copy x independent");
        eq(2.0, copy.getY(), "copy y independent");
        eq(3.0, copy.getWidth(), "copy width independent");
        eq(4.0, copy.getHeight(), "copy height independent");

        DOMRectReadOnly empty = DOMRectReadOnly.fromRect((DOMRectReadOnly) null);
        eq(0.0, empty.getX(), "null rectangle dictionary x");
        eq(0.0, empty.getBottom(), "null rectangle dictionary bottom");
        pass();
    }

    private void rectangleNaNAndSignedZero() {
        DOMRectReadOnly nanWidth = new DOMRectReadOnly(2.0, 3.0, Double.NaN, 4.0);
        yes(Double.isNaN(nanWidth.getLeft()), "NaN width left");
        yes(Double.isNaN(nanWidth.getRight()), "NaN width right");
        eq(3.0, nanWidth.getTop(), "NaN width does not affect top");
        eq(7.0, nanWidth.getBottom(), "NaN width does not affect bottom");

        DOMRectReadOnly mixedZero = new DOMRectReadOnly(-0.0, -0.0, 0.0, 0.0);
        raw(-0.0, mixedZero.getLeft(), "minimum preserves negative zero");
        raw(0.0, mixedZero.getRight(), "maximum prefers positive zero");
        raw(-0.0, mixedZero.getTop(), "top preserves negative zero");
        raw(0.0, mixedZero.getBottom(), "bottom prefers positive zero");

        DOMRectReadOnly infinite = new DOMRectReadOnly(
                Double.POSITIVE_INFINITY,
                0.0,
                Double.NEGATIVE_INFINITY,
                1.0);
        yes(Double.isNaN(infinite.getLeft()), "infinite cancellation left NaN");
        yes(Double.isNaN(infinite.getRight()), "infinite cancellation right NaN");
        pass();
    }

    private void quadCopiesAndSameObjectPoints() {
        DOMPoint source = new DOMPoint(1.0, 2.0, 3.0, 4.0);
        DOMQuad quad = new DOMQuad(source, null, null, null);
        DOMPoint first = quad.getP1();
        same(first, quad.getP1(), "p1 same-object getter");
        notSame(source, first, "quad constructor copies point dictionary");
        eq(1.0, first.getX(), "copied p1 x");
        eq(4.0, first.getW(), "copied p1 w");
        eq(1.0, quad.getP2().getW(), "default p2 w");

        source.setX(99.0);
        eq(1.0, first.getX(), "source mutation does not change quad");

        DOMQuad copy = DOMQuad.fromQuad(quad);
        notSame(quad, copy, "quad copy identity");
        notSame(quad.getP1(), copy.getP1(), "quad point copy identity");
        eq(1.0, copy.getP1().getX(), "quad point copied value");

        DOMQuad dictionary = DOMQuad.fromQuad(
                new DOMPointReadOnly(5.0, 6.0),
                null,
                new DOMPointReadOnly(7.0, 8.0),
                null);
        eq(5.0, dictionary.getP1().getX(), "quad dictionary p1");
        eq(1.0, dictionary.getP2().getW(), "missing quad dictionary p2 default");
        eq(8.0, dictionary.getP3().getY(), "quad dictionary p3");

        DOMQuad empty = DOMQuad.fromQuad((DOMQuad) null);
        eq(0.0, empty.getP4().getX(), "null quad dictionary x");
        eq(1.0, empty.getP4().getW(), "null quad dictionary w");
        pass();
    }

    private void quadFromRectAndLiveBounds() {
        DOMQuad quad = DOMQuad.fromRect(10.0, 20.0, -4.0, 6.0);
        eq(10.0, quad.getP1().getX(), "fromRect p1 x");
        eq(20.0, quad.getP1().getY(), "fromRect p1 y");
        eq(6.0, quad.getP2().getX(), "fromRect p2 x");
        eq(26.0, quad.getP3().getY(), "fromRect p3 y");

        DOMRect bounds = quad.getBounds();
        eq(6.0, bounds.getX(), "negative-width quad bounds x");
        eq(20.0, bounds.getY(), "quad bounds y");
        eq(4.0, bounds.getWidth(), "quad bounds width");
        eq(6.0, bounds.getHeight(), "quad bounds height");

        quad.getP3().setX(30.0);
        quad.getP4().setY(5.0);
        DOMRect changed = quad.getBounds();
        eq(6.0, changed.getX(), "live changed bounds x");
        eq(5.0, changed.getY(), "live changed bounds y");
        eq(24.0, changed.getWidth(), "live changed bounds width");
        eq(21.0, changed.getHeight(), "live changed bounds height");
        pass();
    }

    private void quadNaNSafeBounds() {
        DOMQuad xNan = new DOMQuad(
                new DOMPoint(Double.NaN, 1.0),
                new DOMPoint(2.0, 2.0),
                new DOMPoint(3.0, 3.0),
                new DOMPoint(4.0, 4.0));
        DOMRect xBounds = xNan.getBounds();
        yes(Double.isNaN(xBounds.getX()), "quad NaN x");
        yes(Double.isNaN(xBounds.getWidth()), "quad NaN width");
        eq(1.0, xBounds.getY(), "quad y remains finite");
        eq(3.0, xBounds.getHeight(), "quad height remains finite");

        DOMQuad yNan = new DOMQuad(
                new DOMPoint(1.0, 1.0),
                new DOMPoint(2.0, Double.NaN),
                new DOMPoint(3.0, 3.0),
                new DOMPoint(4.0, 4.0));
        DOMRect yBounds = yNan.getBounds();
        eq(1.0, yBounds.getX(), "quad x remains finite");
        eq(3.0, yBounds.getWidth(), "quad width remains finite");
        yes(Double.isNaN(yBounds.getY()), "quad NaN y");
        yes(Double.isNaN(yBounds.getHeight()), "quad NaN height");
        pass();
    }

    private void pass() {
        passed++;
    }

    private static void yes(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void same(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected same instance");
        }
    }

    private static void notSame(Object expected, Object actual, String label) {
        if (expected == actual) {
            throw new AssertionError(label + ": expected different instances");
        }
    }

    private static void eq(double expected, double actual, String label) {
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void raw(double expected, double actual, String label) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
            throw new AssertionError(
                    label
                            + ": expected raw 0x"
                            + Long.toHexString(Double.doubleToRawLongBits(expected))
                            + ", got 0x"
                            + Long.toHexString(Double.doubleToRawLongBits(actual)));
        }
    }
}
