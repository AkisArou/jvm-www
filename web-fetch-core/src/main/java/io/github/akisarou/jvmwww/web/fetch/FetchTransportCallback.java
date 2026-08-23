package io.github.akisarou.jvmwww.web.fetch;

/** Foreign-thread-safe completion callback used by a Fetch transport. */
public interface FetchTransportCallback {
    void onResponse(FetchTransportResponse response);
    void onFailure(Throwable error);
}
