package io.github.akisarou.jvmwww.web.websocket;

/** Test-only access to the package-private immutable transport request constructor. */
public final class WebSocketTransportTestSupport {
    private WebSocketTransportTestSupport() {}

    public static WebSocketTransportRequest request(String url, String... protocols) {
        return new WebSocketTransportRequest(url, protocols.clone());
    }
}
