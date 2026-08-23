package io.github.akisarou.jvmwww.web.url;

import java.util.Objects;

final class UrlScalar {
    private UrlScalar() {}

    static String fromString(String input, String label) {
        String checked = Objects.requireNonNull(input, label);
        int length = checked.length();
        int firstUnpaired = firstUnpairedSurrogate(checked);
        if (firstUnpaired < 0) return checked;

        StringBuilder result = new StringBuilder(length);
        result.append(checked, 0, firstUnpaired);
        for (int index = firstUnpaired; index < length; index++) {
            char current = checked.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < length && Character.isLowSurrogate(checked.charAt(index + 1))) {
                    result.append(current).append(checked.charAt(++index));
                } else {
                    result.append('\ufffd');
                }
            } else if (Character.isLowSurrogate(current)) {
                result.append('\ufffd');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static int firstUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    index++;
                } else {
                    return index;
                }
            } else if (Character.isLowSurrogate(current)) {
                return index;
            }
        }
        return -1;
    }
}
