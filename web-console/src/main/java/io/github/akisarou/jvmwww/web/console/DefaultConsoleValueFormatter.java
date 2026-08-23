package io.github.akisarou.jvmwww.web.console;

import java.util.Arrays;

/** Deterministic formatter for ordinary Java values in the current static profile. */
public final class DefaultConsoleValueFormatter implements ConsoleValueFormatter {
    public static final DefaultConsoleValueFormatter INSTANCE =
            new DefaultConsoleValueFormatter();

    private DefaultConsoleValueFormatter() {}

    @Override
    public String toStringValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return (String) value;
        if (value instanceof Character) return value.toString();
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Float || value instanceof Double) {
            return numberToString(((Number) value).doubleValue());
        }
        return String.valueOf(value);
    }

    @Override
    public String toIntegerString(Object value) {
        String source = toStringValue(value);
        int length = source.length();
        int index = skipWhitespace(source, 0);
        int sign = 1;
        if (index < length) {
            char first = source.charAt(index);
            if (first == '+' || first == '-') {
                if (first == '-') sign = -1;
                index++;
            }
        }
        int digitsStart = index;
        double parsed = 0.0;
        while (index < length) {
            char current = source.charAt(index);
            if (current < '0' || current > '9') break;
            parsed = parsed * 10.0 + (current - '0');
            index++;
        }
        if (index == digitsStart) return "NaN";
        return numberToString(sign < 0 ? -parsed : parsed);
    }

    @Override
    public String toFloatString(Object value) {
        String source = toStringValue(value);
        int length = source.length();
        int start = skipWhitespace(source, 0);
        if (startsWith(source, start, "Infinity")) return "Infinity";
        if (startsWith(source, start, "+Infinity")) return "Infinity";
        if (startsWith(source, start, "-Infinity")) return "-Infinity";

        int index = start;
        if (index < length && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
            index++;
        }
        int integerStart = index;
        while (index < length && isDigit(source.charAt(index))) index++;
        boolean hasDigits = index > integerStart;
        if (index < length && source.charAt(index) == '.') {
            index++;
            int fractionStart = index;
            while (index < length && isDigit(source.charAt(index))) index++;
            hasDigits |= index > fractionStart;
        }
        if (!hasDigits) return "NaN";

        int exponentStart = index;
        if (index < length && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
            int probe = index + 1;
            if (probe < length && (source.charAt(probe) == '+' || source.charAt(probe) == '-')) {
                probe++;
            }
            int exponentDigits = probe;
            while (probe < length && isDigit(source.charAt(probe))) probe++;
            if (probe > exponentDigits) {
                index = probe;
            } else {
                index = exponentStart;
            }
        }

        try {
            return numberToString(Double.parseDouble(source.substring(start, index)));
        } catch (NumberFormatException ignored) {
            return "NaN";
        }
    }

    @Override
    public String formatObject(Object value, boolean generic) {
        if (value instanceof Object[]) return Arrays.deepToString((Object[]) value);
        if (value instanceof byte[]) return Arrays.toString((byte[]) value);
        if (value instanceof short[]) return Arrays.toString((short[]) value);
        if (value instanceof int[]) return Arrays.toString((int[]) value);
        if (value instanceof long[]) return Arrays.toString((long[]) value);
        if (value instanceof char[]) return Arrays.toString((char[]) value);
        if (value instanceof float[]) return Arrays.toString((float[]) value);
        if (value instanceof double[]) return Arrays.toString((double[]) value);
        if (value instanceof boolean[]) return Arrays.toString((boolean[]) value);
        return toStringValue(value);
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length()) {
            char current = value.charAt(index);
            if (!Character.isWhitespace(current) && current != '\u00a0' && current != '\ufeff') {
                break;
            }
            index++;
        }
        return index;
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean startsWith(String value, int offset, String expected) {
        return offset >= 0
                && offset + expected.length() <= value.length()
                && value.regionMatches(offset, expected, 0, expected.length());
    }

    private static String numberToString(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (value == 0.0) return "0";
        if (value >= Long.MIN_VALUE && value <= Long.MAX_VALUE && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        String result = Double.toString(value);
        int exponent = result.indexOf('E');
        if (exponent >= 0) {
            return result.substring(0, exponent) + 'e' + result.substring(exponent + 1);
        }
        return result;
    }
}
