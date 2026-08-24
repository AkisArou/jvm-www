package io.github.akisarou.jvmwww.web.fetch.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.bodies.Blob;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySource;
import io.github.akisarou.jvmwww.web.bodies.File;
import io.github.akisarou.jvmwww.web.bodies.FormData;
import io.github.akisarou.jvmwww.web.fetch.Fetch;
import io.github.akisarou.jvmwww.web.fetch.FetchTransport;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCall;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCallback;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportResponse;
import io.github.akisarou.jvmwww.web.fetch.Headers;
import io.github.akisarou.jvmwww.web.fetch.Request;
import io.github.akisarou.jvmwww.web.fetch.Response;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Deterministic conformance for immutable Web body integration with buffered Fetch. */
public final class FetchBodyConformance {
    private static final String BOUNDARY = "jvmwwwBoundary0123456789abcdef";
    private int passed;

    public static void main(String[] args) throws Throwable {
        new FetchBodyConformance().run();
    }

    private void run() throws Throwable {
        testBlobAndFileRequestBodies();
        testFormDataRequestSnapshot();
        testBodyValidationShortCircuitsExtraction();
        testResponseBlobAndOneShotState();
        testMimeExtractionExamples();
        testTransportSnapshotIsolation();
        System.out.println("Fetch body conformance: " + passed + " tests passed");
    }

    private void testBlobAndFileRequestBodies() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> {
            Blob blob = new Blob(runtime, "hello", "TEXT/PLAIN");
            Request request = Request.withBody(
                    runtime,
                    "HTTP://Example.TEST:80/a#fragment",
                    "POST",
                    null,
                    blob,
                    null);
            assertEquals(
                    "http://example.test/a#fragment",
                    request.getUrl(),
                    "canonical visible URL");
            assertEquals(
                    "text/plain",
                    request.getHeaders().get("content-type"),
                    "Blob Content-Type inference");
            Fetch.fetch(runtime, transport, request);
            assertEquals(
                    "http://example.test/a",
                    transport.lastRequest.getUrl(),
                    "transport fragment exclusion");
            assertEquals(5L, transport.lastRequest.getBodySize(), "transport body size");
            assertArrayEquals(
                    "hello".getBytes(StandardCharsets.UTF_8),
                    transport.lastRequest.copyBody(),
                    "Blob request bytes");

            Headers explicit = new Headers(runtime);
            explicit.append("Content-Type", "application/custom");
            File file = new File(
                    runtime,
                    new byte[] {1, 2},
                    "x.bin",
                    "application/octet-stream",
                    7L);
            Fetch.fetch(
                    runtime,
                    transport,
                    Request.withBody(
                            runtime,
                            "https://example.test",
                            "PUT",
                            explicit,
                            file,
                            null));
            assertEquals(
                    "application/custom",
                    transport.lastRequest.getHeaderValue(0),
                    "explicit Content-Type wins");
            assertArrayEquals(
                    new byte[] {1, 2},
                    transport.lastRequest.copyBody(),
                    "File bytes");
        });
        fixture.runtime.close();
        pass();
    }

    private void testFormDataRequestSnapshot() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> {
            FormData data = new FormData(runtime, () -> BOUNDARY);
            data.append("a", "1");
            data.append("a", "2");
            Request request = Request.withBody(
                    runtime,
                    "https://example.test/upload",
                    "POST",
                    null,
                    data,
                    null);
            assertEquals(
                    "multipart/form-data; boundary=" + BOUNDARY,
                    request.getHeaders().get("content-type"),
                    "multipart Content-Type inference");

            data.append("later", "mutation");
            Fetch.fetch(runtime, transport, request);
            String body = new String(
                    transport.lastRequest.copyBody(),
                    StandardCharsets.UTF_8);
            assertTrue(
                    body.contains("name=\"a\"\r\n\r\n1")
                            && body.indexOf("\r\n\r\n2") > body.indexOf("\r\n\r\n1"),
                    "duplicates and insertion order");
            assertTrue(!body.contains("mutation"), "Request snapshots FormData once");
            assertTrue(
                    body.endsWith("--" + BOUNDARY + "--\r\n"),
                    "stable captured boundary");
        });
        fixture.runtime.close();
        pass();
    }

    private void testBodyValidationShortCircuitsExtraction() throws Throwable {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        CountingBodySource[] source = new CountingBodySource[1];
        runTurn(first.runtime, runtime -> source[0] = new CountingBodySource(runtime));
        runTurn(first.runtime, runtime -> {
            assertThrows(
                    JsTypeError.class,
                    () -> Request.withBody(
                            runtime,
                            "https://example.test",
                            "GET",
                            null,
                            source[0],
                            null),
                    "GET body");
            assertEquals(0, source[0].snapshots, "GET rejection precedes extraction");
        });
        runTurn(second.runtime, runtime -> {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Request.withBody(
                            runtime,
                            "https://example.test",
                            "POST",
                            null,
                            source[0],
                            null),
                    "cross-runtime body");
            assertEquals(0, source[0].snapshots, "runtime rejection precedes extraction");
        });
        first.runtime.close();
        second.runtime.close();
        pass();
    }

    private void testResponseBlobAndOneShotState() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] fetch = new JsPromise[1];
        runTurn(
                fixture.runtime,
                runtime -> fetch[0] = Fetch.fetch(
                        runtime,
                        transport,
                        "https://example.test"));
        transport.callback.onResponse(new FetchTransportResponse(
                "HTTPS://EXAMPLE.TEST:443/f#x",
                200,
                "OK",
                new String[] {"content-type", "content-type"},
                new String[] {
                        "text/html;charset=gbk;a=b",
                        "text/html;x=y"
                },
                new byte[] {65, 66},
                false));
        fixture.executor.runNext();
        Response response = (Response) fetch[0].getReferencePayload();
        JsPromise[] blobPromise = new JsPromise[1];
        JsPromise[] second = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            blobPromise[0] = response.blob();
            assertTrue(response.isBodyUsed(), "bodyUsed flips synchronously");
            second[0] = response.bytes();
            assertTrue(blobPromise[0].isPending(), "blob waits for microtask checkpoint");
        });
        Blob blob = (Blob) blobPromise[0].getReferencePayload();
        JsPromise[] blobBytes = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            assertEquals(
                    "text/html;x=y;charset=gbk",
                    blob.getType(),
                    "MIME extraction");
            assertEquals(
                    "https://example.test/f",
                    response.getUrl(),
                    "response URL canonical");
            blobBytes[0] = blob.bytes();
        });
        assertArrayEquals(
                new byte[] {65, 66},
                (byte[]) blobBytes[0].getReferencePayload(),
                "response Blob bytes");
        assertTrue(second[0].isRejected(), "response body remains one-shot");
        fixture.runtime.close();
        pass();
    }

    private void testMimeExtractionExamples() throws Throwable {
        assertEquals(
                "text/html",
                fetchBlobType(
                        new String[] {"content-type"},
                        new String[] {"text/plain;charset=gbk, text/html"}),
                "essence change resets charset");
        assertEquals(
                "text/html;x=y",
                fetchBlobType(
                        new String[] {"content-type", "content-type", "content-type"},
                        new String[] {
                                "text/html;charset=gbk",
                                "x/x",
                                "text/html;x=y"
                        }),
                "intervening essence clears remembered charset");
        assertEquals(
                "text/plain;note=\"a,b\"",
                fetchBlobType(
                        new String[] {"content-type"},
                        new String[] {"text/plain;note=\"a,b\""}),
                "quoted comma is not a header split");
        assertEquals(
                "",
                fetchBlobType(
                        new String[] {"content-type", "content-type"},
                        new String[] {"cannot-parse", "*/*"}),
                "invalid and wildcard values fail extraction");
        pass();
    }

    private void testTransportSnapshotIsolation() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> {
            byte[] source = new byte[] {1, 2, 3};
            Request request = new Request(
                    runtime,
                    "https://example.test",
                    "POST",
                    null,
                    source,
                    null);
            source[0] = 9;
            Fetch.fetch(runtime, transport, request);
            byte[] first = transport.lastRequest.copyBody();
            first[1] = 8;
            assertArrayEquals(
                    new byte[] {1, 2, 3},
                    transport.lastRequest.copyBody(),
                    "transport body copy isolation");
        });
        fixture.runtime.close();
        pass();
    }

    private static String fetchBlobType(String[] names, String[] values) throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] fetch = new JsPromise[1];
        runTurn(
                fixture.runtime,
                runtime -> fetch[0] = Fetch.fetch(
                        runtime,
                        transport,
                        "https://example.test"));
        transport.callback.onResponse(new FetchTransportResponse(
                "https://example.test/final",
                200,
                "OK",
                names,
                values,
                new byte[0],
                false));
        fixture.executor.runNext();
        Response response = (Response) fetch[0].getReferencePayload();
        JsPromise[] blobPromise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> blobPromise[0] = response.blob());
        Blob blob = (Blob) blobPromise[0].getReferencePayload();
        String[] type = new String[1];
        runTurn(fixture.runtime, runtime -> type[0] = blob.getType());
        fixture.runtime.close();
        return type[0];
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try {
            task.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    private void pass() { passed++; }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(String expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String label) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + Arrays.toString(expected)
                            + ", got " + Arrays.toString(actual));
        }
    }

    private static void assertThrows(
            Class<? extends Throwable> expected,
            ThrowingRunnable action,
            String label) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(label + ": wrong exception " + error, error);
        }
        throw new AssertionError(label + ": expected " + expected.getName());
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
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
        FetchTransportRequest lastRequest;
        FetchTransportCallback callback;

        @Override
        public FetchTransportCall start(
                FetchTransportRequest request,
                FetchTransportCallback callback) {
            lastRequest = request;
            this.callback = callback;
            return new RecordingCall();
        }
    }

    private static final class CountingBodySource implements BufferedBodySource {
        private final RuntimeInstance runtime;
        int snapshots;

        CountingBodySource(RuntimeInstance runtime) {
            this.runtime = runtime;
        }

        @Override
        public RuntimeInstance getRuntime() {
            return runtime;
        }

        @Override
        public BufferedBodySnapshot snapshot() {
            snapshots++;
            return BufferedBodySnapshot.copyOf(new byte[] {1}, "application/test");
        }
    }
}
