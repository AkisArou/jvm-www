package io.github.akisarou.jvmwww.web.fetch;

/** Replaceable platform transport for buffered Fetch operations. */
public interface FetchTransport {
    /** Starts one request. The callback may be invoked from any thread, including synchronously. */
    FetchTransportCall start(FetchTransportRequest request, FetchTransportCallback callback);
}
