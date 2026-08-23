package io.github.akisarou.jvmwww.web.fetch;

/** Handle returned by a Fetch transport. cancel() must be thread-safe and idempotent. */
public interface FetchTransportCall {
    void cancel();
}
