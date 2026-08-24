package io.github.akisarou.jvmwww.web.fetch;

import java.util.Arrays;

/** Lazy Fetch/MIME extraction shared by Blob and FormData body projections. */
final class FetchMimeType {
    private FetchMimeType() {}

    static String extract(Headers headers) {
        ParsedMimeType parsed = extractParsed(headers);
        return parsed == null ? "" : parsed.serialize();
    }

    static FormDataParameters extractFormDataParameters(Headers headers) {
        ParsedMimeType parsed = extractParsed(headers);
        if (parsed == null) return null;
        if (parsed.hasEssence("application", "x-www-form-urlencoded")) {
            return FormDataParameters.urlEncoded();
        }
        if (parsed.hasEssence("multipart", "form-data")) {
            String boundary = parsed.getParameter("boundary");
            if (boundary == null || boundary.isEmpty()) return null;
            return FormDataParameters.multipart(boundary);
        }
        return null;
    }

    private static ParsedMimeType extractParsed(Headers headers) {
        Extraction extraction = new Extraction();
        int headerCount = headers.size();
        for (int headerIndex = 0; headerIndex < headerCount; headerIndex++) {
            if (!"content-type".equals(headers.getName(headerIndex))) continue;
            String value = headers.getValue(headerIndex);
            int segmentStart = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (quoted) {
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == '"') {
                        quoted = false;
                    }
                } else if (current == '"') {
                    quoted = true;
                } else if (current == ',') {
                    extraction.accept(value, segmentStart, index);
                    segmentStart = index + 1;
                }
            }
            extraction.accept(value, segmentStart, value.length());
        }
        return extraction.result;
    }

    static final class FormDataParameters {
        static final int URL_ENCODED = 1;
        static final int MULTIPART = 2;

        final int kind;
        final String boundary;

        private FormDataParameters(int kind, String boundary) {
            this.kind = kind;
            this.boundary = boundary;
        }

        static FormDataParameters urlEncoded() {
            return new FormDataParameters(URL_ENCODED, null);
        }

        static FormDataParameters multipart(String boundary) {
            return new FormDataParameters(MULTIPART, boundary);
        }
    }

    private static final class Extraction {
        ParsedMimeType result;
        String rememberedType;
        String rememberedSubtype;
        String rememberedCharset;

        void accept(String value, int start, int end) {
            ParsedMimeType parsed = ParsedMimeType.parse(value, start, end);
            if (parsed == null || parsed.isWildcard()) return;
            if (!parsed.sameEssence(rememberedType, rememberedSubtype)) {
                rememberedType = parsed.type;
                rememberedSubtype = parsed.subtype;
                rememberedCharset = parsed.getParameter("charset");
            } else if (parsed.getParameter("charset") == null
                    && rememberedCharset != null) {
                parsed.addParameter("charset", rememberedCharset);
            }
            result = parsed;
        }
    }

    private static final class ParsedMimeType {
        final String type;
        final String subtype;
        private String[] parameterNames;
        private String[] parameterValues;
        private int parameterCount;

        ParsedMimeType(String type, String subtype) {
            this.type = type;
            this.subtype = subtype;
        }

        static ParsedMimeType parse(String input, int inputStart, int inputEnd) {
            int start = inputStart;
            int end = inputEnd;
            while (start < end && isHttpWhitespace(input.charAt(start))) start++;
            while (end > start && isHttpWhitespace(input.charAt(end - 1))) end--;
            if (start == end) return null;

            int slash = -1;
            int semicolon = end;
            for (int index = start; index < end; index++) {
                char current = input.charAt(index);
                if (current == '/' && slash < 0) {
                    slash = index;
                } else if (current == ';') {
                    semicolon = index;
                    break;
                }
            }
            if (slash <= start || slash >= semicolon - 1) return null;
            if (!isToken(input, start, slash)) return null;

            int subtypeEnd = semicolon;
            while (subtypeEnd > slash + 1 && isHttpWhitespace(input.charAt(subtypeEnd - 1))) {
                subtypeEnd--;
            }
            if (subtypeEnd == slash + 1 || !isToken(input, slash + 1, subtypeEnd)) {
                return null;
            }

            ParsedMimeType result = new ParsedMimeType(
                    asciiLowercase(input, start, slash),
                    asciiLowercase(input, slash + 1, subtypeEnd));
            int position = semicolon;
            while (position < end) {
                position++;
                while (position < end && isHttpWhitespace(input.charAt(position))) position++;
                int nameStart = position;
                while (position < end
                        && input.charAt(position) != ';'
                        && input.charAt(position) != '=') {
                    position++;
                }
                int nameEnd = position;
                while (nameEnd > nameStart && isHttpWhitespace(input.charAt(nameEnd - 1))) {
                    nameEnd--;
                }
                if (position >= end || input.charAt(position) != '=') {
                    while (position < end && input.charAt(position) != ';') position++;
                    continue;
                }
                position++;

                String parameterValue;
                if (position < end && input.charAt(position) == '"') {
                    position++;
                    StringBuilder decoded = new StringBuilder();
                    while (position < end) {
                        char current = input.charAt(position++);
                        if (current == '"') break;
                        if (current == '\\' && position < end) {
                            decoded.append(input.charAt(position++));
                        } else {
                            decoded.append(current);
                        }
                    }
                    parameterValue = decoded.toString();
                    while (position < end && input.charAt(position) != ';') position++;
                } else {
                    int valueStart = position;
                    while (position < end && input.charAt(position) != ';') position++;
                    int valueEnd = position;
                    while (valueEnd > valueStart
                            && isHttpWhitespace(input.charAt(valueEnd - 1))) {
                        valueEnd--;
                    }
                    if (valueEnd == valueStart) continue;
                    parameterValue = input.substring(valueStart, valueEnd);
                }

                if (nameStart != nameEnd
                        && isToken(input, nameStart, nameEnd)
                        && isQuotedStringValue(parameterValue)) {
                    String parameterName = asciiLowercase(input, nameStart, nameEnd);
                    if (result.getParameter(parameterName) == null) {
                        result.addParameter(parameterName, parameterValue);
                    }
                }
            }
            return result;
        }

        boolean isWildcard() {
            return "*".equals(type) && "*".equals(subtype);
        }

        boolean hasEssence(String expectedType, String expectedSubtype) {
            return type.equals(expectedType) && subtype.equals(expectedSubtype);
        }

        boolean sameEssence(String otherType, String otherSubtype) {
            return type.equals(otherType) && subtype.equals(otherSubtype);
        }

        String getParameter(String name) {
            for (int index = 0; index < parameterCount; index++) {
                if (parameterNames[index].equals(name)) return parameterValues[index];
            }
            return null;
        }

        void addParameter(String name, String value) {
            if (parameterNames == null) {
                parameterNames = new String[2];
                parameterValues = new String[2];
            } else if (parameterCount == parameterNames.length) {
                int capacity = parameterCount + (parameterCount >> 1) + 1;
                parameterNames = Arrays.copyOf(parameterNames, capacity);
                parameterValues = Arrays.copyOf(parameterValues, capacity);
            }
            parameterNames[parameterCount] = name;
            parameterValues[parameterCount] = value;
            parameterCount++;
        }

        String serialize() {
            StringBuilder output = new StringBuilder(
                    type.length() + subtype.length() + 1 + parameterCount * 12);
            output.append(type).append('/').append(subtype);
            for (int index = 0; index < parameterCount; index++) {
                String name = parameterNames[index];
                String value = parameterValues[index];
                output.append(';').append(name).append('=');
                if (value.isEmpty() || !isToken(value, 0, value.length())) {
                    output.append('"');
                    for (int valueIndex = 0; valueIndex < value.length(); valueIndex++) {
                        char current = value.charAt(valueIndex);
                        if (current == '"' || current == '\\') output.append('\\');
                        output.append(current);
                    }
                    output.append('"');
                } else {
                    output.append(value);
                }
            }
            return output.toString();
        }
    }

    private static boolean isToken(String value, int start, int end) {
        if (start == end) return false;
        for (int index = start; index < end; index++) {
            if (!isTokenCodePoint(value.charAt(index))) return false;
        }
        return true;
    }

    private static boolean isTokenCodePoint(char value) {
        if ((value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')) return true;
        switch (value) {
            case '!': case '#': case '$': case '%': case '&': case '\'': case '*': case '+':
            case '-': case '.': case '^': case '_': case '`': case '|': case '~':
                return true;
            default:
                return false;
        }
    }

    private static boolean isQuotedStringValue(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\t'
                    && (current < 0x20 || current > 0xff || current == 0x7f)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHttpWhitespace(char value) {
        return value == '\t' || value == '\n' || value == '\r' || value == ' ';
    }

    private static String asciiLowercase(String value, int start, int end) {
        boolean changed = false;
        char[] output = new char[end - start];
        for (int index = start; index < end; index++) {
            char current = value.charAt(index);
            if (current >= 'A' && current <= 'Z') {
                current = (char) (current + ('a' - 'A'));
                changed = true;
            }
            output[index - start] = current;
        }
        if (!changed && start == 0 && end == value.length()) return value;
        return new String(output);
    }
}
