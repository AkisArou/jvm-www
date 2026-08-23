package io.github.akisarou.jvmwww.web.fetch.okhttp.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import java.io.IOException;
import java.util.Arrays;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ResponseBody;

/** Shared deterministic fixtures for OkHttp Fetch transport conformance. */
final class OkHttpFetchTestSupport {
    private OkHttpFetchTestSupport() {}

    static okhttp3.Response redirectedResponse(
            FakeCall call,
            int status,
            String message,
            ResponseBody body) {
        okhttp3.Response prior = new okhttp3.Response.Builder()
                .request(call.request())
                .code(302)
                .message("Found")
                .build();
        okhttp3.Request finalRequest = new okhttp3.Request.Builder()
                .url("https://example.test/final")
                .method("GET", null)
                .build();
        return new okhttp3.Response.Builder()
                .request(finalRequest)
                .code(status)
                .message(message)
                .addHeader("x-duplicate", "one")
                .addHeader("x-duplicate", "two")
                .body(body)
                .priorResponse(prior)
                .build();
    }

    static okhttp3.Response response(
            FakeCall call,
            int status,
            String message,
            ResponseBody body) {
        return new okhttp3.Response.Builder()
                .request(call.request())
                .code(status)
                .message(message)
                .body(body)
                .build();
    }

    static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try {
            task.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    static void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    static void assertEquals(String expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    static void assertArrayEquals(byte[] expected, byte[] actual, String label) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + Arrays.toString(expected)
                            + ", got " + Arrays.toString(actual));
        }
    }

    static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }

    static final class RecordingFactory implements Call.Factory {
        volatile FakeCall lastCall;

        @Override
        public Call newCall(okhttp3.Request request) {
            FakeCall call = new FakeCall(request);
            lastCall = call;
            return call;
        }
    }

    static final class FakeCall implements Call {
        private final okhttp3.Request request;
        private volatile Callback callback;
        volatile boolean cancelled;

        FakeCall(okhttp3.Request request) {
            this.request = request;
        }

        @Override
        public okhttp3.Request request() {
            return request;
        }

        @Override
        public void enqueue(Callback value) {
            callback = value;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        void respond(okhttp3.Response response) throws IOException {
            Callback captured = requireCallback();
            captured.onResponse(this, response);
        }

        void fail(IOException error) {
            requireCallback().onFailure(this, error);
        }

        private Callback requireCallback() {
            Callback captured = callback;
            if (captured == null) {
                throw new IllegalStateException("Call has not been enqueued");
            }
            return captured;
        }
    }
}
