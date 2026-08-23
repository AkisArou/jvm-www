package io.github.akisarou.jvmwww.web.url;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.encoding.TextEncoder;
import java.util.Locale;

/** Parser for the selected WHATWG special HTTP(S) URL profile. */
final class UrlParser {
    private UrlParser() {}

    static UrlRecord parse(RuntimeInstance runtime, String input, UrlRecord base) {
        String source = preprocess(input);
        int schemeEnd = findSchemeEnd(source);
        if (schemeEnd >= 0) {
            String scheme = source.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            UrlHostParser.requireSupportedScheme(scheme);
            return parseAbsolute(runtime, source, schemeEnd + 1, scheme);
        }
        if (base == null) {
            throw new JsTypeError("Relative URL requires an http:// or https:// base URL");
        }
        return parseRelative(runtime, source, base);
    }

    static String serialize(UrlRecord url) {
        StringBuilder output = new StringBuilder(32);
        output.append(url.scheme).append("://");
        if (!url.username.isEmpty() || !url.password.isEmpty()) {
            output.append(url.username);
            if (!url.password.isEmpty()) output.append(':').append(url.password);
            output.append('@');
        }
        output.append(url.host);
        if (url.port >= 0) output.append(':').append(url.port);
        UrlPath.appendTo(output, url);
        if (url.query != null) output.append('?').append(url.query);
        if (url.fragment != null) output.append('#').append(url.fragment);
        return output.toString();
    }

    static String serializeOrigin(UrlRecord url) {
        StringBuilder output = new StringBuilder(24);
        output.append(url.scheme).append("://").append(url.host);
        if (url.port >= 0) output.append(':').append(url.port);
        return output.toString();
    }

    static String serializePath(UrlRecord url) {
        return UrlPath.serialize(url);
    }

    static String encodeQuery(RuntimeInstance runtime, String input) {
        return UrlPercentCodec.encode(runtime, input, UrlPercentCodec.SPECIAL_QUERY);
    }

    static String encodeFragment(RuntimeInstance runtime, String input) {
        return UrlPercentCodec.encode(runtime, input, UrlPercentCodec.FRAGMENT);
    }

    private static UrlRecord parseAbsolute(
            RuntimeInstance runtime,
            String source,
            int afterScheme,
            String scheme) {
        int pointer = afterScheme;
        if (pointer + 1 >= source.length()
                || !UrlPath.isSlash(source.charAt(pointer))
                || !UrlPath.isSlash(source.charAt(pointer + 1))) {
            throw new JsTypeError(
                    "Absolute http:// and https:// URLs require an authority introduced by //");
        }
        pointer += 2;
        while (pointer < source.length() && UrlPath.isSlash(source.charAt(pointer))) pointer++;

        int authorityEnd = findAuthorityEnd(source, pointer);
        UrlRecord result = new UrlRecord();
        result.scheme = scheme;
        parseAuthority(runtime, result, source.substring(pointer, authorityEnd));
        parseAbsoluteTail(runtime, result, source.substring(authorityEnd));
        return result;
    }

    private static UrlRecord parseRelative(
            RuntimeInstance runtime,
            String source,
            UrlRecord base) {
        if (source.length() >= 2
                && UrlPath.isSlash(source.charAt(0))
                && UrlPath.isSlash(source.charAt(1))) {
            int pointer = 2;
            while (pointer < source.length() && UrlPath.isSlash(source.charAt(pointer))) pointer++;
            int authorityEnd = findAuthorityEnd(source, pointer);
            UrlRecord result = new UrlRecord();
            result.scheme = base.scheme;
            parseAuthority(runtime, result, source.substring(pointer, authorityEnd));
            parseAbsoluteTail(runtime, result, source.substring(authorityEnd));
            return result;
        }

        UrlRecord result = new UrlRecord(base);
        result.fragment = null;
        if (source.isEmpty()) return result;

        Parts parts = splitParts(source);
        if (!parts.path.isEmpty()) {
            if (UrlPath.isSlash(parts.path.charAt(0))) {
                result.path.clear();
                UrlPath.parse(runtime, result, parts.path, true);
            } else {
                if (!result.path.isEmpty()) result.path.remove(result.path.size() - 1);
                UrlPath.parse(runtime, result, parts.path, false);
            }
            result.query = parts.hasQuery ? encodeQuery(runtime, parts.query) : null;
        } else if (parts.hasQuery) {
            result.query = encodeQuery(runtime, parts.query);
        }
        if (parts.hasFragment) {
            result.fragment = encodeFragment(runtime, parts.fragment);
        }
        return result;
    }

    private static void parseAuthority(
            RuntimeInstance runtime,
            UrlRecord result,
            String authority) {
        if (authority.isEmpty()) throw new JsTypeError("HTTP(S) URL host must not be empty");
        int at = authority.lastIndexOf('@');
        String hostPort = authority;
        if (at >= 0) {
            TextEncoder encoder = new TextEncoder(runtime);
            String credentials = authority.substring(0, at);
            hostPort = authority.substring(at + 1);
            int colon = credentials.indexOf(':');
            String username = colon < 0 ? credentials : credentials.substring(0, colon);
            String password = colon < 0 ? "" : credentials.substring(colon + 1);
            result.username = UrlPercentCodec.encode(encoder, username, UrlPercentCodec.USERINFO);
            result.password = UrlPercentCodec.encode(encoder, password, UrlPercentCodec.USERINFO);
        }
        UrlHostParser.HostPort parsed =
                UrlHostParser.parseHostPort(hostPort, result.scheme, true);
        result.host = parsed.host;
        result.port = parsed.port;
    }

    private static void parseAbsoluteTail(
            RuntimeInstance runtime,
            UrlRecord result,
            String tail) {
        Parts parts = splitParts(tail);
        result.path.clear();
        if (parts.path.isEmpty()) {
            result.path.add("");
        } else {
            UrlPath.parse(runtime, result, parts.path, true);
        }
        result.query = parts.hasQuery ? encodeQuery(runtime, parts.query) : null;
        result.fragment = parts.hasFragment
                ? encodeFragment(runtime, parts.fragment)
                : null;
    }

    private static Parts splitParts(String input) {
        int hash = input.indexOf('#');
        int beforeHash = hash < 0 ? input.length() : hash;
        int question = input.indexOf('?');
        if (question >= beforeHash) question = -1;
        int pathEnd = question < 0 ? beforeHash : question;
        String path = input.substring(0, pathEnd);
        String query = question < 0 ? null : input.substring(question + 1, beforeHash);
        String fragment = hash < 0 ? null : input.substring(hash + 1);
        return new Parts(path, query, fragment, question >= 0, hash >= 0);
    }

    private static String preprocess(String input) {
        String scalar = UrlScalar.fromString(input, "url");
        int start = 0;
        int end = scalar.length();
        while (start < end && scalar.charAt(start) <= 0x20) start++;
        while (end > start && scalar.charAt(end - 1) <= 0x20) end--;
        String trimmed = scalar.substring(start, end);
        int firstIgnored = -1;
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (current == '\t' || current == '\n' || current == '\r') {
                firstIgnored = index;
                break;
            }
        }
        if (firstIgnored < 0) return trimmed;
        StringBuilder result = new StringBuilder(trimmed.length());
        result.append(trimmed, 0, firstIgnored);
        for (int index = firstIgnored; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (current != '\t' && current != '\n' && current != '\r') result.append(current);
        }
        return result.toString();
    }

    private static int findSchemeEnd(String input) {
        if (input.isEmpty() || !isAsciiAlpha(input.charAt(0))) return -1;
        for (int index = 1; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == ':') return index;
            if (!isAsciiAlphaNumeric(current)
                    && current != '+' && current != '-' && current != '.') {
                return -1;
            }
        }
        return -1;
    }

    private static int findAuthorityEnd(String input, int start) {
        for (int index = start; index < input.length(); index++) {
            char current = input.charAt(index);
            if (UrlPath.isSlash(current) || current == '?' || current == '#') return index;
        }
        return input.length();
    }

    private static boolean isAsciiAlpha(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static boolean isAsciiAlphaNumeric(char value) {
        return isAsciiAlpha(value) || (value >= '0' && value <= '9');
    }

    private static final class Parts {
        final String path;
        final String query;
        final String fragment;
        final boolean hasQuery;
        final boolean hasFragment;

        Parts(
                String path,
                String query,
                String fragment,
                boolean hasQuery,
                boolean hasFragment) {
            this.path = path;
            this.query = query;
            this.fragment = fragment;
            this.hasQuery = hasQuery;
            this.hasFragment = hasFragment;
        }
    }
}
