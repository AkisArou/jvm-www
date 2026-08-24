package io.github.akisarou.jvmwww.web.geometry;

/** IEEE-754 helpers matching Geometry Interfaces' NaN-safe extrema. */
final class GeometryMath {
    private GeometryMath() {}

    static double minimum(double first, double second) {
        return Math.min(first, second);
    }

    static double maximum(double first, double second) {
        return Math.max(first, second);
    }

    static double minimum(
            double first,
            double second,
            double third,
            double fourth) {
        return Math.min(Math.min(first, second), Math.min(third, fourth));
    }

    static double maximum(
            double first,
            double second,
            double third,
            double fourth) {
        return Math.max(Math.max(first, second), Math.max(third, fourth));
    }
}
