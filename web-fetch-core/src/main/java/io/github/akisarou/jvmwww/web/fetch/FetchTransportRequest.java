package io.github.akisarou.jvmwww.web.fetch;

/** Immutable transport-safe request snapshot with no owner-confined Web objects. */
public final class FetchTransportRequest {
    private final String url;
    private final String method;
    private final String[] headerNames;
    private final String[] headerValues;
    private final byte[] body;

    FetchTransportRequest(Request request) {
        this.url = request.copyUrlForTransport();
        this.method = request.getMethod();
        Headers headers = request.getHeaders();
        this.headerNames = headers.snapshotNames();
        this.headerValues = headers.snapshotValues();
        this.body = request.copyBodyForTransport();
    }

    public String getUrl() { return url; }
    public String getMethod() { return method; }
    public int getHeaderCount() { return headerNames.length; }
    public String getHeaderName(int index) { return headerNames[index]; }
    public String getHeaderValue(int index) { return headerValues[index]; }
    public byte[] copyBody() { return body == null ? null : body.clone(); }
}
