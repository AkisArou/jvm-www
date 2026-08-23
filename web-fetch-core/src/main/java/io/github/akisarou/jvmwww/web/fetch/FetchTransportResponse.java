package io.github.akisarou.jvmwww.web.fetch;

import java.util.Objects;

/** Immutable, transport-safe buffered response snapshot. */
public final class FetchTransportResponse {
    private final String url;
    private final int status;
    private final String statusText;
    private final String[] headerNames;
    private final String[] headerValues;
    private final byte[] body;
    private final boolean redirected;

    public FetchTransportResponse(
            String url,
            int status,
            String statusText,
            String[] headerNames,
            String[] headerValues,
            byte[] body,
            boolean redirected) {
        this.url = Objects.requireNonNull(url, "url");
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("Fetch transport status must be between 200 and 599");
        }
        this.status = status;
        this.statusText = validateStatusText(statusText == null ? "" : statusText);
        this.headerNames = Objects.requireNonNull(headerNames, "headerNames").clone();
        this.headerValues = Objects.requireNonNull(headerValues, "headerValues").clone();
        if (this.headerNames.length != this.headerValues.length) {
            throw new IllegalArgumentException("Header name/value arrays differ in length");
        }
        this.body = body == null ? new byte[0] : body.clone();
        this.redirected = redirected;
    }

    public String getUrl() { return url; }
    public int getStatus() { return status; }
    public String getStatusText() { return statusText; }
    public boolean isRedirected() { return redirected; }
    public int getHeaderCount() { return headerNames.length; }
    public String getHeaderName(int index) { return headerNames[index]; }
    public String getHeaderValue(int index) { return headerValues[index]; }
    public byte[] copyBody() { return body.clone(); }

    String[] copyHeaderNames() { return headerNames.clone(); }
    String[] copyHeaderValues() { return headerValues.clone(); }

    private static String validateStatusText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("HTTP status text cannot contain CR/LF");
            }
        }
        return value;
    }
}
