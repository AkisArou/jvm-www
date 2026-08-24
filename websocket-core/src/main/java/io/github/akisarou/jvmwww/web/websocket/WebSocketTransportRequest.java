package io.github.akisarou.jvmwww.web.websocket;

import java.util.Objects;

/** Immutable transport-safe connection request with no owner-confined Web values. */
public final class WebSocketTransportRequest {
    private final String url;
    private final String[] protocols;

    WebSocketTransportRequest(String url, String[] validatedProtocols) {
        this.url = Objects.requireNonNull(url, "url");
        this.protocols = Objects.requireNonNull(validatedProtocols, "protocols");
    }

    public String getUrl() { return url; }
    public int getProtocolCount() { return protocols.length; }
    public String getProtocol(int index) { return protocols[index]; }
    public String[] copyProtocols() { return protocols.clone(); }
}
