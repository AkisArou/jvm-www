package io.github.akisarou.jvmwww.web.websocket;

import java.util.Objects;

final class WebSocketScalar {
    private WebSocketScalar() {}

    static String fromString(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        char[] converted = null;
        for (int index = 0; index < checked.length(); index++) {
            char current = checked.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < checked.length() && Character.isLowSurrogate(checked.charAt(index + 1))) {
                    if (converted != null) {
                        converted[index] = current;
                        converted[index + 1] = checked.charAt(index + 1);
                    }
                    index++;
                    continue;
                }
                if (converted == null) converted = checked.toCharArray();
                converted[index] = '\ufffd';
            } else if (Character.isLowSurrogate(current)) {
                if (converted == null) converted = checked.toCharArray();
                converted[index] = '\ufffd';
            }
        }
        return converted == null ? checked : new String(converted);
    }
}
