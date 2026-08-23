package io.github.akisarou.jvmwww.web.fetch.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic conformance for the transport-independent buffered Fetch profile. */
public final class FetchConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new FetchConformance().run();
    }

    private void run() throws Throwable {
        testHeadersNormalizationAndMutation();
        testRequestValidationAndSnapshot();
        testSynchronousTransportCallbackStillSettlesLater();
        testForeignResponseRunsReactionOnOwner();
        testAbortBeforeStartSkipsTransport();
        testAbortAfterStartCancelsAndPreservesReason();
        testNetworkFailureRejectsTypeError();
        testBufferedBodyReadsAreOneShotMicrotasks();
        testResponseWinsAbortRace();
        testCloseCancelsQueuedFetch();
        System.out.println("Fetch core conformance: " + passed + " tests passed");
    }

    private void testHeadersNormalizationAndMutation() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Headers headers = new Headers(runtime);
            headers.append(" X-Test ".trim(), "  one\t");
            headers.append("x-test", "two");
            assertEquals("one, two", headers.get("X-TEST"), "combined values");
            headers.set("X-Test", "three");
            assertEquals("three", headers.get("x-test"), "set replaces values");
            headers.delete("x-test");
            assertTrue(!headers.has("x-test"), "delete removes values");
            assertThrows(JsTypeError.class, () -> headers.append("bad name", "x"), "invalid name");
            assertThrows(JsTypeError.class, () -> headers.append("x", "a\nb"), "invalid value");
        });
        fixture.runtime.close();
        pass();
    }

    private void testRequestValidationAndSnapshot() throws Throwable {
        Fixture fixture = new Fixture();
        byte[] body = new byte[] {1, 2, 3};
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> {
            Headers headers = new Headers(runtime);
            headers.append("X-A", "b");
            Request request = new Request(runtime, "https://example.test/a", "POST", headers, body, null);
            body[0] = 9;
            Fetch.fetch(runtime, transport, request);
            assertEquals("POST", transport.lastRequest.getMethod(), "method normalization");
            assertEquals(1, transport.lastRequest.getHeaderCount(), "header snapshot");
            assertEquals(1, transport.lastRequest.copyBody()[0], "request body copied");
            assertThrows(
                    JsTypeError.class,
                    () -> new Request(runtime, "https://example.test", "GET", null, new byte[0], null),
                    "GET body refused");
            assertThrows(
                    JsTypeError.class,
                    () -> new Request(runtime, "/relative"),
                    "relative URL refused until WHATWG URL module");
        });
        fixture.runtime.close();
        pass();
    }

    private void testSynchronousTransportCallbackStillSettlesLater() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        FetchTransport transport = (request, callback) -> {
            callback.onResponse(okResponse("sync"));
            return new RecordingCall();
        };
        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            trace.add("before");
            promise[0] = Fetch.fetch(runtime, transport, "https://example.test");
            promise[0].then((owner, source, destination) -> trace.add("reaction"), null);
            assertTrue(promise[0].isPending(), "sync transport callback did not settle inline");
            trace.add("after");
        });
        assertListEquals(Arrays.asList("before", "after"), trace, "call return ordering");
        fixture.executor.runNext();
        assertListEquals(Arrays.asList("before", "after", "reaction"), trace, "later reaction");
        fixture.runtime.close();
        pass();
    }

    private void testForeignResponseRunsReactionOnOwner() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        AtomicBoolean reactionOwner = new AtomicBoolean(false);
        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            promise[0] = Fetch.fetch(runtime, transport, "https://example.test");
            promise[0].then((owner, source, destination) -> reactionOwner.set(owner.isOwnerThread()), null);
        });
        Thread worker = new Thread(() -> transport.callback.onResponse(okResponse("foreign")), "fetch-worker");
        worker.start();
        worker.join();
        assertTrue(promise[0].isPending(), "foreign thread cannot settle Promise");
        assertEquals(1, fixture.executor.getPendingCallbackCount(), "one owner wake");
        fixture.executor.runNext();
        assertTrue(reactionOwner.get(), "reaction runs on owner");
        fixture.runtime.close();
        pass();
    }

    private void testAbortBeforeStartSkipsTransport() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        AbortController[] controller = new AbortController[1];
        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            controller[0] = new AbortController(runtime);
            controller[0].abortNumber(7.0);
            Request request = new Request(runtime, "https://example.test", "GET", null, null, controller[0].getSignal());
            promise[0] = Fetch.fetch(runtime, transport, request);
            assertEquals(0, transport.starts, "aborted request never starts transport");
        });
        fixture.executor.runNext();
        assertTrue(promise[0].isRejected(), "aborted fetch rejects");
        assertEquals(7.0, promise[0].getNumberPayload(), "abort reason preserved");
        fixture.runtime.close();
        pass();
    }

    private void testAbortAfterStartCancelsAndPreservesReason() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        AbortController[] controller = new AbortController[1];
        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            controller[0] = new AbortController(runtime);
            Request request = new Request(runtime, "https://example.test", "GET", null, null, controller[0].getSignal());
            promise[0] = Fetch.fetch(runtime, transport, request);
            controller[0].abortBoolean(true);
        });
        assertTrue(transport.call.cancelled, "abort cancels transport");
        fixture.executor.runNext();
        assertTrue(promise[0].isRejected(), "aborted fetch rejects");
        assertTrue(promise[0].getBooleanPayload(), "boolean abort reason preserved");
        fixture.runtime.close();
        pass();
    }

    private void testNetworkFailureRejectsTypeError() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> promise[0] = Fetch.fetch(runtime, transport, "https://example.test"));
        transport.callback.onFailure(new IllegalStateException("boom"));
        fixture.executor.runNext();
        assertTrue(promise[0].isRejected(), "network failure rejects");
        assertTrue(promise[0].getReferencePayload() instanceof JsTypeError, "network failure is TypeError");
        fixture.runtime.close();
        pass();
    }

    private void testBufferedBodyReadsAreOneShotMicrotasks() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] fetchPromise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> fetchPromise[0] = Fetch.fetch(runtime, transport, "https://example.test"));
        transport.callback.onResponse(okResponse("hello"));
        fixture.executor.runNext();
        Response response = (Response) fetchPromise[0].getReferencePayload();
        JsPromise[] text = new JsPromise[1];
        JsPromise[] second = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            text[0] = response.text();
            assertTrue(response.isBodyUsed(), "bodyUsed flips synchronously");
            second[0] = response.arrayBuffer();
            assertTrue(text[0].isPending(), "body read waits for microtask checkpoint");
        });
        assertEquals("hello", text[0].getReferencePayload(), "UTF-8 text body");
        assertTrue(second[0].isRejected(), "second body consumption rejects");
        fixture.runtime.close();
        pass();
    }

    private void testResponseWinsAbortRace() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        AbortController[] controller = new AbortController[1];
        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            controller[0] = new AbortController(runtime);
            promise[0] = Fetch.fetch(runtime, transport,
                    new Request(runtime, "https://example.test", "GET", null, null, controller[0].getSignal()));
        });
        transport.callback.onResponse(okResponse("winner"));
        runTurn(fixture.runtime, runtime -> controller[0].abortNumber(9.0));
        fixture.executor.runNext();
        assertTrue(promise[0].isFulfilled(), "first response completion wins abort race");
        fixture.runtime.close();
        pass();
    }

    private void testCloseCancelsQueuedFetch() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> Fetch.fetch(runtime, transport, "https://example.test"));
        transport.callback.onResponse(okResponse("queued"));
        fixture.runtime.close();
        assertTrue(transport.call.cancelled, "runtime close cancels undelivered fetch");
        fixture.executor.runAll();
        pass();
    }

    private static FetchTransportResponse okResponse(String body) {
        return new FetchTransportResponse(
                "https://example.test/final",
                200,
                "OK",
                new String[] {"content-type"},
                new String[] {"text/plain"},
                body.getBytes(StandardCharsets.UTF_8),
                false);
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
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void assertEquals(double expected, double actual, String label) {
        if (Double.compare(expected, actual) != 0) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void assertEquals(String expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void assertListEquals(List<String> expected, List<String> actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable action, String label) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(label + ": wrong exception " + error, error);
        }
        throw new AssertionError(label + ": expected " + expected.getName());
    }

    private interface ThrowingRunnable { void run() throws Throwable; }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }

    private static final class RecordingCall implements FetchTransportCall {
        volatile boolean cancelled;
        @Override public void cancel() { cancelled = true; }
    }

    private static final class RecordingTransport implements FetchTransport {
        int starts;
        FetchTransportRequest lastRequest;
        FetchTransportCallback callback;
        final RecordingCall call = new RecordingCall();

        @Override
        public FetchTransportCall start(FetchTransportRequest request, FetchTransportCallback callback) {
            starts++;
            lastRequest = request;
            this.callback = callback;
            return call;
        }
    }
}
