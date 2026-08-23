package io.github.akisarou.jvmwww.web.url;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.encoding.TextEncoder;

/** UTF-8 percent encoding for the URL component sets reached by this profile. */
final class UrlPercentCodec {
    static final int USERINFO = 1;
    static final int PATH = 2;
    static final int SPECIAL_QUERY = 3;
    static final int FRAGMENT = 4;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UrlPercentCodec() {}

    static String encode(RuntimeInstance runtime, String input, int set) {
        return encode(new TextEncoder(runtime), input, set);
    }

    static String encode(TextEncoder encoder, String input, int set) {
        String scalar = UrlScalar.fromString(input, "input");
        byte[] bytes = encoder.encode(scalar);
        StringBuilder output = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int current = value & 0xff;
            if (shouldEncode(current, set)) {
                output.append('%')
                        .append(HEX[current >>> 4])
                        .append(HEX[current & 0x0f]);
            } else {
                output.append((char) current);
            }
        }
        return output.toString();
    }

    private static boolean shouldEncode(int value, int set) {
        if (value <= 0x1f || value > 0x7e) return true;
        if (set == FRAGMENT) {
            return value == 0x20
                    || value == '"'
                    || value == '<'
                    || value == '>'
                    || value == '`';
        }
        if (set == SPECIAL_QUERY) {
            return value == 0x20
                    || value == '"'
                    || value == '#'
                    || value == '<'
                    || value == '>'
                    || value == '\'';
        }
        if (set == PATH) {
            return value == 0x20
                    || value == '"'
                    || value == '#'
                    || value == '<'
                    || value == '>'
                    || value == '?'
                    || value == '^'
                    || value == '`'
                    || value == '{'
                    || value == '}';
        }
        if (set == USERINFO) {
            if (value == 0x20
                    || value == '"'
                    || value == '#'
                    || value == '<'
                    || value == '>'
                    || value == '?'
                    || value == '^'
                    || value == '`'
                    || value == '{'
                    || value == '}') {
                return true;
            }
            return value == '/'
                    || value == ':'
                    || value == ';'
                    || value == '='
                    || value == '@'
                    || value == '['
                    || value == '\\'
                    || value == ']'
                    || value == '|';
        }
        throw new AssertionError("Unknown URL percent-encode set: " + set);
    }
}
