package io.github.akisarou.jvmwww.web.fetch.testkit;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.fetch.Fetch;
import io.github.akisarou.jvmwww.web.fetch.FetchTransport;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCall;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCallback;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest;
import io.github.akisarou.jvmwww.web.fetch.Headers;
import io.github.akisarou.jvmwww.web.fetch.Request;
import io.github.akisarou.jvmwww.web.url.URL;
import io.github.akisarou.jvmwww.web.url.URLSearchParams;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Deterministic conformance for string and URLSearchParams Fetch BodyInit adapters. */
public final class FetchBodyInitConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new FetchBodyInitConformance().run();
    }

    private void run() throws Throwable {
        testStringBodyExactUtf8AndInference();
        testSearchParamsBodyExactBytesAndSnapshot();
        testEmptyBodiesRemainPresent();
        testExplicitTypeAndRuntimeIsolation();
        System.out.println("Fetch BodyInit conformance: " + passed + " tests passed");
    }

    private void testStringBodyExactUtf8AndInference() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> {
            URL url = new URL(runtime, "HTTPS://Example.TEST:443/upload#fragment");
            Request request = Request.withStringBody(
                    runtime,
                    url,
                    "POST",
                    null,
                    "A\ud800\ud83d\udca9",
                    null);
            assertEquals(
                    "text/plain;charset=UTF-8",
                    request.getHeaders().get("content-type"),
                    "string Content-Type inference");
            Fetch.fetch(runtime, transport, request);
            assertEquals(
                    "https://example.test/upload",
                    transport.lastRequest.getUrl(),
                    "canonical fragment-free transport URL");
            assertArrayEquals(
                    new byte[] {
                            0x41,
                            (byte) 0xef, (byte) 0xbf, (byte) 0xbd,
                            (byte) 0xf0, (byte) 0x9f, (byte) 0x92, (byte) 0xa9
                    },
                    transport.lastRequest.copyBody(),
                    "scalar UTF-8 string bytes");
        });
        fixture.runtime.close();
        pass();
    }

    private void testSearchParamsBodyExactBytesAndSnapshot() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> {
            URLSearchParams params = new URLSearchParams(runtime);
            params.append("a", "1 2");
            params.append("x", "~");
            params.append("u", "\ud800");
            params.append("a", "3");

            byte[] first = params.copyFormEncodedBytes();
            first[0] = 'z';
            assertArrayEquals(
                    ascii("a=1+2&x=%7E&u=%EF%BF%BD&a=3"),
                    params.copyFormEncodedBytes(),
                    "direct form bytes are independent");

            Request request = Request.withSearchParamsBody(
                    runtime,
                    "https://example.test/form",
                    "POST",
                    null,
                    params,
                    null);
            assertEquals(
                    "application/x-www-form-urlencoded;charset=UTF-8",
                    request.getHeaders().get("content-type"),
                    "URLSearchParams Content-Type inference");

            params.set("a", "later");
            params.append("new", "mutation");
            Fetch.fetch(runtime, transport, request);
            assertArrayEquals(
                    ascii("a=1+2&x=%7E&u=%EF%BF%BD&a=3"),
                    transport.lastRequest.copyBody(),
                    "Request freezes URLSearchParams bytes");
        });
        fixture.runtime.close();
        pass();
    }

    private void testEmptyBodiesRemainPresent() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> {
            Request emptyString = Request.withStringBody(
                    runtime,
                    "https://example.test/string",
                    "POST",
                    null,
                    "",
                    null);
            Fetch.fetch(runtime, transport, emptyString);
            assertEquals(0L, transport.lastRequest.getBodySize(), "empty string body size");
            assertEquals(
                    "text/plain;charset=UTF-8",
                    emptyString.getHeaders().get("content-type"),
                    "empty string still infers type");

            URLSearchParams params = new URLSearchParams(runtime);
            Request emptyParams = Request.withSearchParamsBody(
                    runtime,
                    "https://example.test/params",
                    "POST",
                    null,
                    params,
                    null);
            Fetch.fetch(runtime, transport, emptyParams);
            assertEquals(0L, transport.lastRequest.getBodySize(), "empty params body size");
            assertEquals(
                    "application/x-www-form-urlencoded;charset=UTF-8",
                    emptyParams.getHeaders().get("content-type"),
                    "empty params still infer type");

            assertThrows(
                    JsTypeError.class,
                    () -> Request.withStringBody(
                            runtime,
                            "https://example.test",
                            "GET",
                            null,
                            "",
                            null),
                    "empty string is still a GET body");
            assertThrows(
                    JsTypeError.class,
                    () -> Request.withSearchParamsBody(
                            runtime,
                            "https://example.test",
                            "HEAD",
                            null,
                            params,
                            null),
                    "empty params are still a HEAD body");
        });
        fixture.runtime.close();
        pass();
    }

    private void testExplicitTypeAndRuntimeIsolation() throws Throwable {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        URLSearchParams[] params = new URLSearchParams[1];
        runTurn(first.runtime, runtime -> {
            params[0] = new URLSearchParams(runtime, "a=b");
            Headers headers = new Headers(runtime);
            headers.append("content-type", "application/custom");
            Request request = Request.withSearchParamsBody(
                    runtime,
                    "https://example.test",
                    "POST",
                    headers,
                    params[0],
                    null);
            assertEquals(
                    "application/custom",
                    request.getHeaders().get("content-type"),
                    "explicit Content-Type wins");
        });
        runTurn(second.runtime, runtime -> assertThrows(
                IllegalArgumentException.class,
                () -> Request.withSearchParamsBody(
                        runtime,
                        "https://example.test",
                        "POST",
                        null,
                        params[0],
                        null),
                "cross-runtime URLSearchParams body"));
        first.runtime.close();
        second.runtime.close();
        pass();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try {
            task.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    private void pass() {
        passed++;
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

        @Override
        public FetchTransportCall start(
                FetchTransportRequest request,
                FetchTransportCallback callback) {
            lastRequest = request;
            return new RecordingCall();
        }
    }
}
