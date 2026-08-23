package io.github.akisarou.jvmwww.web.fetch.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.fetch.Fetch;
import io.github.akisarou.jvmwww.web.fetch.FetchTransport;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCall;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCallback;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportResponse;
import io.github.akisarou.jvmwww.web.fetch.Response;

/** Cross-slice proof that Fetch text consumption uses the Web Encoding UTF-8 algorithm. */
public final class FetchEncodingConformance {
    public static void main(String[] args) throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] fetchPromise = new JsPromise[1];
        runTurn(
                fixture.runtime,
                runtime ->
                        fetchPromise[0] =
                                Fetch.fetch(runtime, transport, "https://example.test/text"));

        transport.callback.onResponse(
                new FetchTransportResponse(
                        "https://example.test/text",
                        200,
                        "OK",
                        new String[] {"content-type"},
                        new String[] {"text/plain"},
                        new byte[] {
                            (byte) 0xef, (byte) 0xbb, (byte) 0xbf,
                            0x41,
                            (byte) 0xe2, 0x28, (byte) 0xa1
                        },
                        false));
        fixture.executor.runNext();

        Response response = (Response) fetchPromise[0].getReferencePayload();
        JsPromise[] textPromise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> textPromise[0] = response.text());
        assertEquals(
                "A\ufffd(\ufffd",
                textPromise[0].getReferencePayload(),
                "Fetch text uses BOM-aware WHATWG replacement boundaries");

        fixture.runtime.close();
        System.out.println("Fetch encoding integration conformance: 1 test passed");
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try {
            task.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    private static void assertEquals(String expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }

    private static final class RecordingCall implements FetchTransportCall {
        @Override
        public void cancel() {}
    }

    private static final class RecordingTransport implements FetchTransport {
        FetchTransportCallback callback;

        @Override
        public FetchTransportCall start(
                FetchTransportRequest request,
                FetchTransportCallback callback) {
            this.callback = callback;
            return new RecordingCall();
        }
    }
}
