package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.encoding.TextDecoder;
import io.github.akisarou.jvmwww.web.url.FormUrlEncodedParser;
import java.util.Objects;

/** Bounded direct-byte parsers used by Fetch Response.formData(). */
public final class FormDataParser {
    static final int MAX_MULTIPART_PARTS = 1024;
    static final int MAX_PART_HEADER_BYTES = 64 * 1024;
    static final int MAX_PART_HEADERS = 64;
    static final int MAX_BOUNDARY_LENGTH = 70;

    private FormDataParser() {}

    /** Parses directly from the immutable snapshot without first decoding the complete body. */
    public static FormData parseUrlEncoded(
            RuntimeInstance runtime,
            BufferedBodySnapshot snapshot) {
        RuntimeInstance checkedRuntime = Objects.requireNonNull(runtime, "runtime");
        BodyRuntimeChecks.assertLanguageExecution(checkedRuntime);
        FormData result = new FormData(checkedRuntime);
        FormUrlEncodedParser.parse(
                checkedRuntime,
                Objects.requireNonNull(snapshot, "snapshot").ownedBytes(),
                result);
        return result;
    }

    /** Parses a bounded multipart body while sharing immutable File byte ranges. */
    public static FormData parseMultipart(
            RuntimeInstance runtime,
            BufferedBodySnapshot snapshot,
            String boundary) {
        RuntimeInstance checkedRuntime = Objects.requireNonNull(runtime, "runtime");
        BodyRuntimeChecks.assertLanguageExecution(checkedRuntime);
        byte[] input = Objects.requireNonNull(snapshot, "snapshot").ownedBytes();
        byte[] boundaryBytes = validateBoundary(boundary);
        return new MultipartParser(checkedRuntime, input, boundaryBytes).parse();
    }

    private static byte[] validateBoundary(String value) {
        String boundary = Objects.requireNonNull(value, "boundary");
        int length = boundary.length();
        if (length == 0 || length > MAX_BOUNDARY_LENGTH) {
            throw malformed("multipart boundary length is outside 1..70");
        }
        byte[] output = new byte[length];
        for (int index = 0; index < length; index++) {
            char current = boundary.charAt(index);
            if (!isBoundaryCharacter(current)
                    || (current == ' ' && index == length - 1)) {
                throw malformed("multipart boundary contains an invalid character");
            }
            output[index] = (byte) current;
        }
        return output;
    }

    private static boolean isBoundaryCharacter(char value) {
        if ((value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')) return true;
        switch (value) {
            case '\'': case '(': case ')': case '+': case '_': case ',': case '-':
            case '.': case '/': case ':': case '=': case '?': case ' ':
                return true;
            default:
                return false;
        }
    }

    private static JsTypeError malformed(String detail) {
        return new JsTypeError("Malformed form data: " + detail);
    }

    private static final class MultipartParser {
        private static final int SUFFIX_INVALID = 0;
        private static final int SUFFIX_NEXT = 1;
        private static final int SUFFIX_FINAL = 2;

        private final RuntimeInstance runtime;
        private final byte[] input;
        private final byte[] boundary;
        private final TextDecoder decoder;
        private final FormData output;

        private String partName;
        private String partFilename;
        private int partContentTypeStart;
        private int partContentTypeEnd;
        private long fileLastModified = Long.MIN_VALUE;
        private boolean filenamePresent;
        private boolean dispositionSeen;
        private int parameterEnd;
        private String parameterValue;

        MultipartParser(RuntimeInstance runtime, byte[] input, byte[] boundary) {
            this.runtime = runtime;
            this.input = input;
            this.boundary = boundary;
            this.decoder = new TextDecoder(runtime, "utf-8", false, true);
            this.output = new FormData(runtime);
        }

        FormData parse() {
            int marker = findFirstBoundary();
            if (marker < 0) throw malformed("opening multipart boundary was not found");
            int suffix = boundarySuffix(marker);
            if (suffix == SUFFIX_FINAL) return output;
            if (suffix != SUFFIX_NEXT) throw malformed("opening boundary has an invalid suffix");
            int cursor = marker + 2 + boundary.length + 2;
            int parts = 0;
            while (true) {
                if (++parts > MAX_MULTIPART_PARTS) {
                    throw malformed("multipart part count exceeds profile limit");
                }
                resetPartHeaders();
                int bodyStart = parseHeaders(cursor);
                int delimiter = findNextBoundary(bodyStart);
                if (delimiter < 0) throw malformed("closing multipart boundary was not found");
                emitPart(bodyStart, delimiter);

                marker = delimiter + 2;
                suffix = boundarySuffix(marker);
                if (suffix == SUFFIX_FINAL) return output;
                if (suffix != SUFFIX_NEXT) throw malformed("multipart boundary has an invalid suffix");
                cursor = marker + 2 + boundary.length + 2;
            }
        }

        private int findFirstBoundary() {
            int patternLength = boundary.length + 2;
            for (int index = 0; index <= input.length - patternLength; index++) {
                if (input[index] != '-' || input[index + 1] != '-') continue;
                if (index != 0
                        && (index < 2 || input[index - 2] != '\r' || input[index - 1] != '\n')) {
                    continue;
                }
                if (!matchesBoundary(index)) continue;
                if (boundarySuffix(index) != SUFFIX_INVALID) return index;
            }
            return -1;
        }

        private int findNextBoundary(int start) {
            int minimum = boundary.length + 4;
            for (int index = start; index <= input.length - minimum; index++) {
                if (input[index] != '\r'
                        || input[index + 1] != '\n'
                        || input[index + 2] != '-'
                        || input[index + 3] != '-') continue;
                int marker = index + 2;
                if (matchesBoundary(marker)
                        && boundarySuffix(marker) != SUFFIX_INVALID) {
                    return index;
                }
            }
            return -1;
        }

        private boolean matchesBoundary(int marker) {
            int boundaryStart = marker + 2;
            if (boundaryStart + boundary.length > input.length) return false;
            for (int index = 0; index < boundary.length; index++) {
                if (input[boundaryStart + index] != boundary[index]) return false;
            }
            return true;
        }

        private int boundarySuffix(int marker) {
            int after = marker + 2 + boundary.length;
            if (after + 1 < input.length
                    && input[after] == '-'
                    && input[after + 1] == '-') {
                int tail = after + 2;
                if (tail == input.length) return SUFFIX_FINAL;
                if (tail + 1 < input.length
                        && input[tail] == '\r'
                        && input[tail + 1] == '\n') return SUFFIX_FINAL;
                return SUFFIX_INVALID;
            }
            if (after + 1 < input.length
                    && input[after] == '\r'
                    && input[after + 1] == '\n') return SUFFIX_NEXT;
            return SUFFIX_INVALID;
        }

        private int parseHeaders(int start) {
            int position = start;
            int count = 0;
            while (true) {
                int lineEnd = findCrlf(position);
                if (lineEnd < 0 || lineEnd + 2 - start > MAX_PART_HEADER_BYTES) {
                    throw malformed("multipart part headers exceed profile limit or are unterminated");
                }
                if (lineEnd == position) {
                    if (!dispositionSeen || partName == null) {
                        throw malformed("multipart part lacks form-data name");
                    }
                    return lineEnd + 2;
                }
                if (++count > MAX_PART_HEADERS) {
                    throw malformed("multipart part header count exceeds profile limit");
                }
                parseHeaderLine(position, lineEnd);
                position = lineEnd + 2;
            }
        }

        private int findCrlf(int start) {
            int limit = Math.min(input.length - 1, start + MAX_PART_HEADER_BYTES);
            for (int index = start; index < limit; index++) {
                if (input[index] == '\r' && input[index + 1] == '\n') return index;
            }
            return -1;
        }

        private void parseHeaderLine(int start, int end) {
            int colon = -1;
            for (int index = start; index < end; index++) {
                int current = input[index] & 0xff;
                if (current == ':') {
                    colon = index;
                    break;
                }
                if (!isTokenByte(current)) throw malformed("multipart header name is invalid");
            }
            if (colon <= start) throw malformed("multipart header lacks a name or colon");
            int valueStart = colon + 1;
            while (valueStart < end && isOptionalWhitespace(input[valueStart] & 0xff)) valueStart++;
            int valueEnd = end;
            while (valueEnd > valueStart
                    && isOptionalWhitespace(input[valueEnd - 1] & 0xff)) valueEnd--;
            for (int index = valueStart; index < valueEnd; index++) {
                int current = input[index] & 0xff;
                if (current != '\t' && (current < 0x20 || current == 0x7f)) {
                    throw malformed("multipart header value contains a control byte");
                }
            }

            if (asciiEquals(start, colon, "content-disposition")) {
                if (dispositionSeen) throw malformed("multipart part has duplicate Content-Disposition");
                dispositionSeen = true;
                parseContentDisposition(valueStart, valueEnd);
            } else if (asciiEquals(start, colon, "content-type")
                    && partContentTypeStart < 0) {
                partContentTypeStart = valueStart;
                partContentTypeEnd = valueEnd;
            }
        }

        private void parseContentDisposition(int start, int end) {
            int position = start;
            while (position < end && isOptionalWhitespace(input[position] & 0xff)) position++;
            int typeStart = position;
            while (position < end && isTokenByte(input[position] & 0xff)) position++;
            if (!asciiEquals(typeStart, position, "form-data")) {
                throw malformed("Content-Disposition is not form-data");
            }
            while (position < end && isOptionalWhitespace(input[position] & 0xff)) position++;
            while (position < end) {
                if (input[position] != ';') {
                    throw malformed("Content-Disposition parameter delimiter is invalid");
                }
                position++;
                while (position < end && isOptionalWhitespace(input[position] & 0xff)) position++;
                int nameStart = position;
                while (position < end && isTokenByte(input[position] & 0xff)) position++;
                int nameEnd = position;
                if (nameEnd == nameStart) throw malformed("Content-Disposition parameter name is empty");
                if (containsByte(nameStart, nameEnd, '*')) {
                    throw malformed("extended Content-Disposition parameters are unsupported");
                }
                while (position < end && isOptionalWhitespace(input[position] & 0xff)) position++;
                if (position >= end || input[position] != '=') {
                    throw malformed("Content-Disposition parameter lacks equals");
                }
                position++;
                while (position < end && isOptionalWhitespace(input[position] & 0xff)) position++;
                if (position < end && input[position] == '"') {
                    decodeQuotedParameter(position + 1, end);
                } else {
                    decodeBareParameter(position, end);
                }
                position = parameterEnd;

                if (asciiEquals(nameStart, nameEnd, "name")) {
                    if (partName == null) partName = parameterValue;
                } else if (asciiEquals(nameStart, nameEnd, "filename")) {
                    if (!filenamePresent) {
                        filenamePresent = true;
                        partFilename = parameterValue;
                    }
                }
            }
        }

        private void decodeQuotedParameter(int start, int end) {
            int close = -1;
            for (int position = start; position < end; position++) {
                int current = input[position] & 0xff;
                if (current == '"') {
                    close = position;
                    break;
                }
                if (current != '\t' && (current < 0x20 || current == 0x7f)) {
                    throw malformed("quoted parameter contains a control byte");
                }
            }
            if (close < 0) throw malformed("quoted parameter is unterminated");
            parameterValue = decodeDispositionParameter(start, close);
            int position = close + 1;
            while (position < end && isOptionalWhitespace(input[position] & 0xff)) position++;
            if (position < end && input[position] != ';') {
                throw malformed("quoted parameter has trailing bytes");
            }
            parameterEnd = position;
        }

        private void decodeBareParameter(int start, int end) {
            int position = start;
            while (position < end && input[position] != ';') position++;
            int valueEnd = position;
            while (valueEnd > start && isOptionalWhitespace(input[valueEnd - 1] & 0xff)) valueEnd--;
            if (valueEnd == start) throw malformed("bare parameter value is empty");
            for (int index = start; index < valueEnd; index++) {
                if (!isTokenByte(input[index] & 0xff)) {
                    throw malformed("bare parameter value is not an HTTP token");
                }
            }
            parameterValue = decodeDispositionParameter(start, valueEnd);
            parameterEnd = position;
        }

        /**
         * Reverses only the three escapes emitted by the HTML multipart serializer. Treating every
         * percent triplet as an escape would corrupt literal names such as "%41". Backslashes are
         * literal because modern browser multipart quoting does not use quoted-pair escaping.
         */
        private String decodeDispositionParameter(int start, int end) {
            int escapeCount = 0;
            for (int index = start; index + 2 < end; index++) {
                if (input[index] == '%' && isMultipartParameterEscape(index)) {
                    escapeCount++;
                    index += 2;
                }
            }
            if (escapeCount == 0) {
                return decoder.decode(input, start, end - start);
            }
            byte[] decoded = new byte[end - start - escapeCount * 2];
            int written = 0;
            for (int index = start; index < end; index++) {
                if (input[index] == '%'
                        && index + 2 < end
                        && isMultipartParameterEscape(index)) {
                    decoded[written++] = (byte) ((hexValue(input[index + 1] & 0xff) << 4)
                            | hexValue(input[index + 2] & 0xff));
                    index += 2;
                } else {
                    decoded[written++] = input[index];
                }
            }
            if (written != decoded.length) {
                throw new AssertionError("Exact multipart parameter decode length was not filled");
            }
            return decoder.decode(decoded);
        }

        private boolean isMultipartParameterEscape(int percentIndex) {
            int high = hexValue(input[percentIndex + 1] & 0xff);
            int low = hexValue(input[percentIndex + 2] & 0xff);
            if (high < 0 || low < 0) return false;
            int value = (high << 4) | low;
            return value == '\r' || value == '\n' || value == '"';
        }

        private void emitPart(int bodyStart, int bodyEnd) {
            int length = bodyEnd - bodyStart;
            if (filenamePresent) {
                String type = partContentTypeStart < 0
                        ? "text/plain"
                        : decodeIsomorphic(partContentTypeStart, partContentTypeEnd);
                if (type.isEmpty()) type = "text/plain";
                if (fileLastModified == Long.MIN_VALUE) {
                    fileLastModified = System.currentTimeMillis();
                }
                File file = new File(
                        runtime,
                        BlobData.singleView(input, bodyStart, length),
                        type,
                        partFilename,
                        fileLastModified);
                output.appendParsedFile(partName, file);
            } else {
                output.appendParsedString(
                        partName,
                        decoder.decode(input, bodyStart, length));
            }
        }

        private String decodeIsomorphic(int start, int end) {
            char[] output = new char[end - start];
            for (int index = start; index < end; index++) {
                output[index - start] = (char) (input[index] & 0xff);
            }
            return new String(output);
        }

        private boolean containsByte(int start, int end, int expected) {
            for (int index = start; index < end; index++) {
                if ((input[index] & 0xff) == expected) return true;
            }
            return false;
        }

        private boolean asciiEquals(int start, int end, String expected) {
            if (end - start != expected.length()) return false;
            for (int index = 0; index < expected.length(); index++) {
                int actual = input[start + index] & 0xff;
                if (actual >= 'A' && actual <= 'Z') actual += 'a' - 'A';
                if (actual != expected.charAt(index)) return false;
            }
            return true;
        }

        private void resetPartHeaders() {
            partName = null;
            partFilename = null;
            partContentTypeStart = -1;
            partContentTypeEnd = -1;
            filenamePresent = false;
            dispositionSeen = false;
            parameterEnd = 0;
            parameterValue = null;
        }

        private static int hexValue(int value) {
            if (value >= '0' && value <= '9') return value - '0';
            if (value >= 'A' && value <= 'F') return value - 'A' + 10;
            if (value >= 'a' && value <= 'f') return value - 'a' + 10;
            return -1;
        }

        private static boolean isOptionalWhitespace(int value) {
            return value == ' ' || value == '\t';
        }

        private static boolean isTokenByte(int value) {
            if ((value >= 'a' && value <= 'z')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9')) return true;
            switch (value) {
                case '!': case '#': case '$': case '%': case '&': case '\'': case '*':
                case '+': case '-': case '.': case '^': case '_': case '`': case '|': case '~':
                    return true;
                default:
                    return false;
            }
        }
    }
}
