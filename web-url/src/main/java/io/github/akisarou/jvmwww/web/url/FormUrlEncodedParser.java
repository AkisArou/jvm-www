package io.github.akisarou.jvmwww.web.url;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Objects;

/** Shared direct-byte form parser for URLSearchParams and Fetch FormData consumption. */
public final class FormUrlEncodedParser {
    private FormUrlEncodedParser() {}

    /** Parses immediately, does not retain or mutate {@code input}, and emits entries in order. */
    public static void parse(
            RuntimeInstance runtime,
            byte[] input,
            FormUrlEncodedConsumer consumer) {
        Objects.requireNonNull(runtime, "runtime");
        UrlRuntimeChecks.assertLanguageExecution(runtime);
        FormUrlCodec.parseBytes(
                runtime,
                Objects.requireNonNull(input, "input"),
                Objects.requireNonNull(consumer, "consumer"));
    }
}
