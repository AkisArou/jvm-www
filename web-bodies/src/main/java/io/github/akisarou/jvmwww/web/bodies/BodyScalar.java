package io.github.akisarou.jvmwww.web.bodies;

import java.util.Objects;

final class BodyScalar {
    private BodyScalar() {}

    static String fromString(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        int firstSurrogate = -1;
        for (int index = 0; index < checked.length(); index++) {
            char current = checked.charAt(index);
            if (Character.isHighSurrogate(current)
                    && index + 1 < checked.length()
                    && Character.isLowSurrogate(checked.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(current)) {
                firstSurrogate = index;
                break;
            }
        }
        if (firstSurrogate < 0) return checked;

        StringBuilder result = new StringBuilder(checked.length());
        result.append(checked, 0, firstSurrogate);
        int index = firstSurrogate;
        while (index < checked.length()) {
            char current = checked.charAt(index);
            if (Character.isHighSurrogate(current)
                    && index + 1 < checked.length()
                    && Character.isLowSurrogate(checked.charAt(index + 1))) {
                result.append(current).append(checked.charAt(index + 1));
                index += 2;
            } else if (Character.isSurrogate(current)) {
                result.append('\ufffd');
                index++;
            } else {
                result.append(current);
                index++;
            }
        }
        return result.toString();
    }
}
