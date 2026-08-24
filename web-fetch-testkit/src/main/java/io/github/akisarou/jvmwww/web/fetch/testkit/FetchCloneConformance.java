package io.github.akisarou.jvmwww.web.fetch.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.bodies.FormData;
import io.github.akisarou.jvmwww.web.events.AbortController;
import io.github.akisarou.jvmwww.web.fetch.Fetch;
import io.github.akisarou.jvmwww.web.fetch.FetchTransport;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCall;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCallback;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportResponse;
import io.github.akisarou.jvmwww.web.fetch.Headers;
import io.github.akisarou.jvmwww.web.fetch.Request;
import io.github.akisarou.jvmwww.web.fetch.Response;
import io.github.akisarou.jvmwww.web.url.URLSearchParams;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Deterministic conformance for buffered Request/Response clones and Request Body reads. */
public final class FetchCloneConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new FetchCloneConformance().run();
    }

    private void run() throws Throwable {
        testRequestCloneSharesBodyAndFollowsSignal();
        testNullRequestBodyRemainsReusable();
        testRequestFormDataAndTextReadersAreIndependentAcrossClone();
        testFetchClaimsRequestBodySynchronously();
        testResponseCloneSharesBodyWithIndependentConsumption();
        testCloneOwnerConfinement();
        System.out.println("Fetch clone conformance: " + passed + " tests passed");
    }

    private void testRequestCloneSharesBodyAndFollowsSignal() throws Throwable {
        Fixture fixture = new Fixture();
        Request[] source = new Request[1];
        Request[] clone = new Request[1];
        JsPromise[] sourceText = new JsPromise[1];
        JsPromise[] cloneBytes = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            Headers headers = new Headers(runtime);
            headers.append("x-copy", "original");
            AbortController controller = new AbortController(runtime);
            source[0] = Request.withStringBody(
                    runtime,
                    "https://example.test/upload",
                    "POST",
                    headers,
                    "alpha \ud83d\udca9",
                    controller.getSignal());
            clone[0] = source[0].clone();

            assertNotSame(source[0], clone[0], "Request object identity");
            assertNotSame(source[0].getHeaders(), clone[0].getHeaders(), "header object identity");
            assertEquals("original", clone[0].getHeaders().get("x-copy"), "cloned header value");
            source[0].getHeaders().set("x-copy", "changed");
            assertEquals("original", clone[0].getHeaders().get("x-copy"), "header mutation isolation");

            assertNotSame(source[0].getSignal(), clone[0].getSignal(), "dependent signal identity");
            controller.abortNumber(7.0);
            assertTrue(clone[0].getSignal().isAborted(), "dependent clone signal aborts");
            assertEquals(7.0, clone[0].getSignal().getReasonNumber(), "dependent signal reason");

            sourceText[0] = source[0].text();
            cloneBytes[0] = clone[0].bytes();
            assertTrue(source[0].isBodyUsed(), "source body used synchronously");
            assertTrue(clone[0].isBodyUsed(), "clone body used independently");
            assertThrows(JsTypeError.class, source[0]::clone, "used Request cannot clone");
        });

        assertEquals("alpha \ud83d\udca9", sourceText[0].getReferencePayload(), "source text");
        assertArrayEquals(
                "alpha \ud83d\udca9".getBytes(StandardCharsets.UTF_8),
                (byte[]) cloneBytes[0].getReferencePayload(),
                "clone bytes");
        fixture.close();
        pass();
    }

    private void testNullRequestBodyRemainsReusable() throws Throwable {
        Fixture fixture = new Fixture();
        Request[] request = new Request[1];
        Request[] clone = new Request[1];
        JsPromise[] first = new JsPromise[1];
        JsPromise[] second = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            request[0] = new Request(runtime, "https://example.test/no-body");
            first[0] = request[0].text();
            second[0] = request[0].bytes();
            clone[0] = request[0].clone();
            assertTrue(!request[0].isBodyUsed(), "null body is never disturbed");
            assertTrue(!clone[0].isBodyUsed(), "null-body clone starts unused");
        });
        assertEquals("", first[0].getReferencePayload(), "null body text");
        assertArrayEquals(new byte[0], (byte[]) second[0].getReferencePayload(), "null body bytes");
        fixture.close();
        pass();
    }

    private void testRequestFormDataAndTextReadersAreIndependentAcrossClone() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] parsed = new JsPromise[1];
        JsPromise[] text = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            URLSearchParams params = new URLSearchParams(runtime);
            params.append("a", "one two");
            params.append("a", "\ud83d\udca9");
            Request request = Request.withSearchParamsBody(
                    runtime,
                    "https://example.test/form",
                    "POST",
                    null,
                    params,
                    null);
            Request clone = request.clone();
            parsed[0] = request.formData();
            text[0] = clone.text();
        });

        FormData data = (FormData) parsed[0].getReferencePayload();
        runTurn(fixture.runtime, runtime -> {
            assertEquals(2, data.size(), "parsed entry count");
            assertEquals("one two", data.getStringValue(0), "first parsed value");
            assertEquals("\ud83d\udca9", data.getStringValue(1), "second parsed value");
        });
        assertEquals("a=one+two&a=%F0%9F%92%A9", text[0].getReferencePayload(), "clone form text");
        fixture.close();
        pass();
    }

    private void testFetchClaimsRequestBodySynchronously() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] rejectedRead = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            Request request = Request.withStringBody(
                    runtime,
                    "https://example.test/send",
                    "POST",
                    null,
                    "payload",
                    null);
            Fetch.fetch(runtime, transport, request);
            assertTrue(request.isBodyUsed(), "fetch claims body before returning");
            assertEquals(1, transport.starts, "transport starts once");
            assertThrows(JsTypeError.class, request::clone, "fetched Request cannot clone");
            assertThrows(
                    JsTypeError.class,
                    () -> Fetch.fetch(runtime, transport, request),
                    "used Request cannot be fetched twice");
            assertEquals(1, transport.starts, "second fetch skipped transport");
            rejectedRead[0] = request.text();
        });
        assertTrue(rejectedRead[0].isRejected(), "read after fetch rejects");
        assertTrue(
                rejectedRead[0].getReferencePayload() instanceof JsTypeError,
                "read-after-fetch rejection type");
        fixture.close();
        pass();
    }

    private void testResponseCloneSharesBodyWithIndependentConsumption() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] fetchPromise = new JsPromise[1];
        runTurn(fixture.runtime, runtime ->
                fetchPromise[0] = Fetch.fetch(runtime, transport, "https://example.test/data"));
        transport.callback.onResponse(new FetchTransportResponse(
                "https://example.test/final",
                200,
                "OK",
                new String[] {"content-type", "x-value"},
                new String[] {"application/x-www-form-urlencoded", "one"},
                "a=1&a=2".getBytes(StandardCharsets.US_ASCII),
                true));
        fixture.executor.runNext();

        JsPromise[] parsed = new JsPromise[1];
        JsPromise[] text = new JsPromise[1];
        Response[] response = new Response[1];
        Response[] clone = new Response[1];
        runTurn(fixture.runtime, runtime -> {
            response[0] = (Response) fetchPromise[0].getReferencePayload();
            clone[0] = response[0].clone();
            assertNotSame(response[0], clone[0], "Response object identity");
            assertNotSame(response[0].getHeaders(), clone[0].getHeaders(), "Response header identity");
            assertEquals(response[0].getUrl(), clone[0].getUrl(), "Response URL");
            assertEquals(response[0].getStatus(), clone[0].getStatus(), "Response status");
            assertTrue(clone[0].isRedirected(), "redirected metadata cloned");
            assertThrows(
                    JsTypeError.class,
                    () -> clone[0].getHeaders().append("x", "y"),
                    "cloned Response headers remain immutable");

            parsed[0] = response[0].formData();
            text[0] = clone[0].text();
            assertTrue(response[0].isBodyUsed(), "response source used");
            assertTrue(clone[0].isBodyUsed(), "response clone used independently");
            assertThrows(JsTypeError.class, response[0]::clone, "used Response cannot clone");
        });

        FormData data = (FormData) parsed[0].getReferencePayload();
        runTurn(fixture.runtime, runtime -> {
            assertEquals(2, data.size(), "response parsed entry count");
            assertEquals("1", data.getStringValue(0), "response first value");
            assertEquals("2", data.getStringValue(1), "response second value");
        });
        assertEquals("a=1&a=2", text[0].getReferencePayload(), "response clone text");
        fixture.close();
        pass();
    }

    private void testCloneOwnerConfinement() throws Throwable {
        Fixture fixture = new Fixture();
        Request[] request = new Request[1];
        runTurn(fixture.runtime, runtime ->
                request[0] = Request.withStringBody(
                        runtime,
                        "https://example.test/owner",
                        "POST",
                        null,
                        "x",
                        null));
        assertThrows(IllegalStateException.class, request[0]::clone, "Request clone outside turn");
        fixture.close();
        pass();
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

    private static void assertNotSame(Object first, Object second, String label) {
        if (first == second) throw new AssertionError(label);
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(double expected, double actual, String label) {
        if (Double.compare(expected, actual) != 0) {
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

        void close() {
            runtime.close();
            executor.runAll();
        }
    }

    private static final class RecordingCall implements FetchTransportCall {
        volatile boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private static final class RecordingTransport implements FetchTransport {
        int starts;
        FetchTransportCallback callback;
        final RecordingCall call = new RecordingCall();

        @Override
        public FetchTransportCall start(
                FetchTransportRequest request,
                FetchTransportCallback callback) {
            starts++;
            this.callback = callback;
            return call;
        }
    }
}
