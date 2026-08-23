package io.github.akisarou.jvmwww.web.url;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.encoding.TextEncoder;

/** Path parsing and serialization for special HTTP(S) URL records. */
final class UrlPath {
    private UrlPath() {}

    static void replace(RuntimeInstance runtime, UrlRecord url, String input) {
        url.path.clear();
        parse(runtime, url, UrlScalar.fromString(input, "pathname"), true);
    }

    static void parse(
            RuntimeInstance runtime,
            UrlRecord result,
            String input,
            boolean stripLeadingSlash) {
        String normalized = input.replace('\\', '/');
        TextEncoder encoder = new TextEncoder(runtime);
        int pointer = stripLeadingSlash && !normalized.isEmpty() && normalized.charAt(0) == '/'
                ? 1
                : 0;
        int segmentStart = pointer;
        for (int index = pointer; index <= normalized.length(); index++) {
            if (index != normalized.length() && normalized.charAt(index) != '/') continue;
            String segment = normalized.substring(segmentStart, index);
            boolean last = index == normalized.length();
            if (isSingleDot(segment)) {
                if (last) appendTrailingEmpty(result);
            } else if (isDoubleDot(segment)) {
                if (!result.path.isEmpty()) result.path.remove(result.path.size() - 1);
                if (last) appendTrailingEmpty(result);
            } else {
                result.path.add(
                        UrlPercentCodec.encode(encoder, segment, UrlPercentCodec.PATH));
            }
            segmentStart = index + 1;
        }
        if (result.path.isEmpty()) result.path.add("");
    }

    static String serialize(UrlRecord url) {
        StringBuilder output = new StringBuilder();
        appendTo(output, url);
        return output.toString();
    }

    static void appendTo(StringBuilder output, UrlRecord url) {
        if (url.path.isEmpty()) {
            output.append('/');
            return;
        }
        for (String segment : url.path) output.append('/').append(segment);
    }

    static boolean isSlash(char value) {
        return value == '/' || value == '\\';
    }

    private static void appendTrailingEmpty(UrlRecord result) {
        if (result.path.isEmpty() || !result.path.get(result.path.size() - 1).isEmpty()) {
            result.path.add("");
        }
    }

    private static boolean isSingleDot(String segment) {
        return ".".equals(segment) || "%2e".equalsIgnoreCase(segment);
    }

    private static boolean isDoubleDot(String segment) {
        return "..".equals(segment)
                || ".%2e".equalsIgnoreCase(segment)
                || "%2e.".equalsIgnoreCase(segment)
                || "%2e%2e".equalsIgnoreCase(segment);
    }
}
