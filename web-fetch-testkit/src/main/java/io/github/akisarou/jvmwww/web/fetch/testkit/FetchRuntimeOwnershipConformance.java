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

/** Conformance for RuntimeInstance ownership of active Fetch transports. */
public final class FetchRuntimeOwnershipConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new FetchRuntimeOwnershipConformance().run();
    }

    private void run() throws Throwable {
        testRuntimeCloseCancelsFetchWithoutCompletion();
        testQueuedCompletionRemainsOwnedUntilOwnerDelivery();
        testDeliveredCompletionReleasesRuntimeOwnership();
        System.out.println("Fetch runtime ownership: " + passed + " tests passed");
    }

    private void testRuntimeCloseCancelsFetchWithoutCompletion() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime ->
                Fetch.fetch(runtime, transport, "https://example.test/open"));

        assertEquals(0, transport.call.cancelCount, "open fetch not cancelled before close");
        fixture.runtime.close();
        assertEquals(1, transport.call.cancelCount, "runtime close cancels open fetch");
        fixture.executor.runAll();
        pass();
    }

    private void testQueuedCompletionRemainsOwnedUntilOwnerDelivery() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime ->
                Fetch.fetch(runtime, transport, "https://example.test/queued"));

        transport.callback.onResponse(okResponse());
        fixture.runtime.close();
        assertEquals(
                1,
                transport.call.cancelCount,
                "runtime close cancels a claimed completion not yet delivered");
        fixture.executor.runAll();
        pass();
    }

    private void testDeliveredCompletionReleasesRuntimeOwnership() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        final JsPromise[] result = new JsPromise[1];
        runTurn(fixture.runtime, runtime ->
                result[0] = Fetch.fetch(runtime, transport, "https://example.test/done"));

        transport.callback.onResponse(okResponse());
        fixture.executor.runAll();
        assertTrue(result[0].isFulfilled(), "response delivered");
        fixture.runtime.close();
        assertEquals(
                0,
                transport.call.cancelCount,
                "delivered Fetch operation released runtime ownership");
        pass();
    }

    private static FetchTransportResponse okResponse() {
        return new FetchTransportResponse(
                "https://example.test/final",
                204,
                "No Content",
                new String[0],
                new String[0],
                new byte[0],
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

    private void pass() {
        passed++;
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }

    private static final class RecordingTransport implements FetchTransport {
        final RecordingCall call = new RecordingCall();
        FetchTransportCallback callback;

        @Override
        public FetchTransportCall start(
                FetchTransportRequest request,
                FetchTransportCallback callback) {
            this.callback = callback;
            return call;
        }
    }

    private static final class RecordingCall implements FetchTransportCall {
        int cancelCount;

        @Override
        public void cancel() {
            cancelCount++;
        }
    }
}
