package io.github.akisarou.jvmwww.web.fetch.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
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
import io.github.akisarou.jvmwww.web.fetch.FetchTransportResponse;
import io.github.akisarou.jvmwww.web.fetch.Request;
import io.github.akisarou.jvmwww.web.fetch.Response;
import io.github.akisarou.jvmwww.web.url.URL;

/** Deterministic conformance for the canonical URL boundary shared by Fetch and web-url. */
public final class FetchUrlConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new FetchUrlConformance().run();
    }

    private void run() throws Throwable {
        testStringAndUrlInputsCanonicalizeAndSnapshot();
        testCredentialsAndCrossRuntimeUrlsAreRefused();
        testResponseUrlCanonicalizationAndFragmentRemoval();
        testInvalidTransportResponseUrlRejects();
        System.out.println("Fetch URL conformance: " + passed + " tests passed");
    }

    private void testStringAndUrlInputsCanonicalizeAndSnapshot() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime -> {
            Request fromString = new Request(
                    runtime,
                    " \nHTTP://Example.COM:80/a/../b c?x=1 2#frag ");
            assertEquals(
                    "http://example.com/b%20c?x=1%202#frag",
                    fromString.getUrl(),
                    "Request.url preserves canonical fragment");
            Fetch.fetch(runtime, transport, fromString);
            assertEquals(
                    "http://example.com/b%20c?x=1%202",
                    transport.lastRequest.getUrl(),
                    "transport target excludes fragment");

            URL input = new URL(runtime, "https://Example.Test:443/x/./y?q=a b#f");
            Request fromUrl = new Request(runtime, input);
            input.setPathname("/changed");
            input.setHash("");
            assertEquals(
                    "https://example.test/x/y?q=a%20b#f",
                    fromUrl.getUrl(),
                    "Request snapshots URL input");
            Fetch.fetch(runtime, transport, fromUrl);
            assertEquals(
                    "https://example.test/x/y?q=a%20b",
                    transport.lastRequest.getUrl(),
                    "URL input uses canonical transport target");

            Fetch.fetch(
                    runtime,
                    transport,
                    new URL(runtime, "https://Direct.Test/a#ignored"));
            assertEquals(
                    "https://direct.test/a",
                    transport.lastRequest.getUrl(),
                    "fetch URL overload");
        });
        fixture.runtime.close();
        pass();
    }

    private void testCredentialsAndCrossRuntimeUrlsAreRefused() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            assertThrows(
                    JsTypeError.class,
                    () -> new Request(runtime, "https://user:pass@example.test/"),
                    "string credentials refused");
            URL credentialed = new URL(runtime, "https://user@example.test/");
            assertThrows(
                    JsTypeError.class,
                    () -> new Request(runtime, credentialed),
                    "URL credentials refused");
            assertThrows(
                    JsTypeError.class,
                    () -> new Request(runtime, "/relative"),
                    "relative input still requires an environment base");
        });

        Fixture other = new Fixture();
        URL[] otherUrl = new URL[1];
        runTurn(other.runtime, runtime ->
                otherUrl[0] = new URL(runtime, "https://other.test/"));
        runTurn(fixture.runtime, runtime ->
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new Request(runtime, otherUrl[0]),
                        "cross-runtime URL refused"));
        fixture.runtime.close();
        other.runtime.close();
        pass();
    }

    private void testResponseUrlCanonicalizationAndFragmentRemoval() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime ->
                promise[0] = Fetch.fetch(runtime, transport, "https://example.test/"));
        transport.callback.onResponse(new FetchTransportResponse(
                " HTTP://Example.Test:80/a/../b#transport-fragment ",
                200,
                "OK",
                new String[0],
                new String[0],
                new byte[0],
                true));
        fixture.executor.runNext();
        assertTrue(promise[0].isFulfilled(), "valid final URL fulfills Fetch");
        Response response = (Response) promise[0].getReferencePayload();
        runTurn(fixture.runtime, runtime -> {
            assertEquals("http://example.test/b", response.getUrl(), "Response.url canonicalized");
            assertTrue(response.isRedirected(), "redirect flag preserved");
        });
        fixture.runtime.close();
        pass();
    }

    private void testInvalidTransportResponseUrlRejects() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime ->
                promise[0] = Fetch.fetch(runtime, transport, "https://example.test/"));
        transport.callback.onResponse(new FetchTransportResponse(
                "ftp://example.test/not-in-profile",
                200,
                "OK",
                new String[0],
                new String[0],
                new byte[0],
                false));
        fixture.executor.runNext();
        assertTrue(promise[0].isRejected(), "invalid final URL rejects Fetch");
        Object reason = promise[0].getReferencePayload();
        assertTrue(reason instanceof JsTypeError, "invalid final URL is a network TypeError");
        assertTrue(((JsTypeError) reason).getCause() instanceof JsTypeError, "parse failure retained");
        fixture.runtime.close();
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

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
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
        @Override public void cancel() {}
    }

    private static final class RecordingTransport implements FetchTransport {
        FetchTransportRequest lastRequest;
        FetchTransportCallback callback;
        final RecordingCall call = new RecordingCall();

        @Override
        public FetchTransportCall start(
                FetchTransportRequest request,
                FetchTransportCallback callback) {
            lastRequest = request;
            this.callback = callback;
            return call;
        }
    }
}
