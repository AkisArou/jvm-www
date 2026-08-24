package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.events.AbortSignal;
import io.github.akisarou.jvmwww.web.url.URL;
import java.util.Objects;

/** Entry points for the transport-independent buffered Fetch profile. */
public final class Fetch {
    private Fetch() {}

    public static JsPromise fetch(
            RuntimeInstance runtime,
            FetchTransport transport,
            String url) {
        return fetch(runtime, transport, new Request(runtime, url));
    }

    public static JsPromise fetch(
            RuntimeInstance runtime,
            FetchTransport transport,
            URL url) {
        return fetch(runtime, transport, new Request(runtime, url));
    }

    public static JsPromise fetch(
            RuntimeInstance runtime,
            FetchTransport transport,
            Request request) {
        RuntimeInstance checkedRuntime = Objects.requireNonNull(runtime, "runtime");
        FetchRuntimeChecks.assertLanguageExecution(checkedRuntime);
        FetchTransport checkedTransport = Objects.requireNonNull(transport, "transport");
        Request checkedRequest = Objects.requireNonNull(request, "request");
        if (checkedRequest.getRuntime() != checkedRuntime) {
            throw new IllegalArgumentException("Request belongs to another RuntimeInstance");
        }

        FetchTransportRequest transportRequest = new FetchTransportRequest(checkedRequest);
        AbortSignal signal = checkedRequest.getSignal();
        FetchOperation operation = new FetchOperation(checkedRuntime, signal);
        operation.start(checkedTransport, transportRequest);
        return operation;
    }
}
