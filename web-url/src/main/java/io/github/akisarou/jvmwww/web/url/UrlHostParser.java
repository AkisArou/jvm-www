package io.github.akisarou.jvmwww.web.url;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import java.util.Locale;

/** Host, port, and reached-scheme parsing for the selected HTTP(S) URL profile. */
final class UrlHostParser {
    private UrlHostParser() {}

    static HostPort parseHostPort(String input, String scheme, boolean portAllowed) {
        String checked = UrlScalar.fromString(input, "host");
        if (checked.indexOf('@') >= 0 || checked.indexOf('/') >= 0
                || checked.indexOf('\\') >= 0 || checked.indexOf('?') >= 0
                || checked.indexOf('#') >= 0) {
            throw new JsTypeError("Invalid URL host");
        }
        if (checked.indexOf('[') >= 0 || checked.indexOf(']') >= 0) {
            throw new JsTypeError("IPv6 hosts are not reached by the current URL profile");
        }

        int colon = checked.lastIndexOf(':');
        boolean portSpecified = colon >= 0;
        String hostInput = portSpecified ? checked.substring(0, colon) : checked;
        if (!portAllowed && portSpecified) {
            throw new JsTypeError("Hostname must not include a port");
        }
        String host = canonicalHost(hostInput);
        int port = -1;
        if (portSpecified && colon + 1 < checked.length()) {
            port = parsePort(checked.substring(colon + 1));
            if (port == defaultPort(scheme)) port = -1;
        }
        return new HostPort(host, port, portSpecified);
    }

    static int parsePort(String input) {
        if (input.isEmpty()) return -1;
        int value = 0;
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current < '0' || current > '9') {
                throw new JsTypeError("Invalid URL port");
            }
            value = value * 10 + (current - '0');
            if (value > 65535) throw new JsTypeError("URL port is out of range");
        }
        return value;
    }

    static int defaultPort(String scheme) {
        if ("http".equals(scheme)) return 80;
        if ("https".equals(scheme)) return 443;
        throw new AssertionError("Unsupported URL scheme: " + scheme);
    }

    static void requireSupportedScheme(String scheme) {
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new JsTypeError(
                    "Current URL profile supports http:// and https:// URLs only");
        }
    }

    private static String canonicalHost(String input) {
        if (input.isEmpty()) throw new JsTypeError("HTTP(S) URL host must not be empty");
        for (int index = 0; index < input.length(); index++) {
            if (input.charAt(index) > 0x7f) {
                throw new JsTypeError(
                        "Internationalized hosts require the unreached IDNA URL slice");
            }
        }
        String lower = input.toLowerCase(Locale.ROOT);
        boolean numeric = true;
        for (int index = 0; index < lower.length(); index++) {
            char current = lower.charAt(index);
            if (current != '.' && (current < '0' || current > '9')) numeric = false;
            if (current == '%' || current <= 0x20 || current == 0x7f
                    || current == '#' || current == '/' || current == ':'
                    || current == '<' || current == '>' || current == '?'
                    || current == '@' || current == '[' || current == '\\'
                    || current == ']' || current == '^' || current == '|') {
                throw new JsTypeError("Invalid URL host");
            }
        }
        if (numeric) return canonicalIpv4(lower);
        if (endsInNumber(lower)) {
            throw new JsTypeError(
                    "Numeric-ending domains require the unreached legacy IPv4 parser");
        }

        int effectiveLength = lower.endsWith(".") ? lower.length() - 1 : lower.length();
        if (effectiveLength <= 0 || effectiveLength > 253) {
            throw new JsTypeError("URL domain length is out of range");
        }
        int labelStart = 0;
        for (int index = 0; index <= effectiveLength; index++) {
            if (index != effectiveLength && lower.charAt(index) != '.') continue;
            int labelLength = index - labelStart;
            if (labelLength <= 0 || labelLength > 63) {
                throw new JsTypeError("Invalid URL domain label");
            }
            if (lower.charAt(labelStart) == '-' || lower.charAt(index - 1) == '-') {
                throw new JsTypeError("URL domain labels must not start or end with '-'");
            }
            for (int labelIndex = labelStart; labelIndex < index; labelIndex++) {
                char current = lower.charAt(labelIndex);
                if (!isAsciiAlphaNumeric(current) && current != '-') {
                    throw new JsTypeError(
                            "Current URL profile accepts ASCII domain labels only");
                }
            }
            labelStart = index + 1;
        }
        return lower;
    }

    private static String canonicalIpv4(String input) {
        String source = input.endsWith(".") ? input.substring(0, input.length() - 1) : input;
        int[] pieces = new int[4];
        int piece = 0;
        int start = 0;
        for (int index = 0; index <= source.length(); index++) {
            if (index != source.length() && source.charAt(index) != '.') continue;
            if (piece >= 4 || index == start) {
                throw new JsTypeError(
                        "Current URL profile requires four decimal IPv4 pieces");
            }
            if (index - start > 1 && source.charAt(start) == '0') {
                throw new JsTypeError(
                        "Legacy octal/hexadecimal IPv4 syntax is not reached");
            }
            int value = 0;
            for (int digit = start; digit < index; digit++) {
                value = value * 10 + (source.charAt(digit) - '0');
                if (value > 255) throw new JsTypeError("IPv4 piece is out of range");
            }
            pieces[piece++] = value;
            start = index + 1;
        }
        if (piece != 4) {
            throw new JsTypeError(
                    "Current URL profile requires four decimal IPv4 pieces");
        }
        return pieces[0] + "." + pieces[1] + "." + pieces[2] + "." + pieces[3];
    }

    private static boolean endsInNumber(String host) {
        int end = host.endsWith(".") ? host.length() - 1 : host.length();
        int start = host.lastIndexOf('.', end - 1) + 1;
        if (start >= end) return false;
        boolean decimal = true;
        for (int index = start; index < end; index++) {
            char current = host.charAt(index);
            if (current < '0' || current > '9') {
                decimal = false;
                break;
            }
        }
        if (decimal) return true;
        if (end - start < 2 || host.charAt(start) != '0'
                || (host.charAt(start + 1) != 'x' && host.charAt(start + 1) != 'X')) {
            return false;
        }
        for (int index = start + 2; index < end; index++) {
            char current = host.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f')
                    || (current >= 'A' && current <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlphaNumeric(char value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9');
    }

    static final class HostPort {
        final String host;
        final int port;
        final boolean portSpecified;

        HostPort(String host, int port, boolean portSpecified) {
            this.host = host;
            this.port = port;
            this.portSpecified = portSpecified;
        }
    }
}
