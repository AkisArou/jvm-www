package io.github.akisarou.jvmwww.web.fetch.okhttp.testkit;

import static io.github.akisarou.jvmwww.web.fetch.okhttp.testkit.OkHttpFetchTestSupport.*;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.web.events.AbortController;
import io.github.akisarou.jvmwww.web.fetch.Fetch;
import io.github.akisarou.jvmwww.web.fetch.Headers;
import io.github.akisarou.jvmwww.web.fetch.Request;
import io.github.akisarou.jvmwww.web.fetch.Response;
import io.github.akisarou.jvmwww.web.fetch.okhttp.OkHttpFetchTransport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** Deterministic conformance for the buffered OkHttp Fetch transport adapter. */
public final class OkHttpFetchTransportConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new OkHttpFetchTransportConformance().run();
    }

    private void run() throws Throwable {
        testRequestMappingPreservesSnapshotsAndDuplicateHeaders();
        testBodylessRequiredMethodsUseAnEmptyBody();
        testForeignResponseIsBufferedClosedAndDeliveredOnOwner();
        testAuthorizationFollowUpIsNotReportedAsRedirect();
        testBodyReadFailureBecomesNetworkErrorAndClosesResponse();
        testAbortCancelsTheExactOkHttpCall();
        testUnsupportedFinalStatusBecomesNetworkError();
        System.out.println("OkHttp Fetch transport conformance: " + passed + " tests passed");
    }

    private void testRequestMappingPreservesSnapshotsAndDuplicateHeaders() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingFactory factory = new RecordingFactory();
        OkHttpFetchTransport transport = new OkHttpFetchTransport(factory);
        byte[] sourceBody = new byte[] {1, 2, 3};

        runTurn(fixture.runtime, runtime -> {
            Headers headers = new Headers(runtime);
            headers.append("X-Test", "one");
            headers.append("x-test", "two");
            Request request = new Request(
                    runtime,
                    "https://example.test/request",
                    "POST",
                    headers,
                    sourceBody,
                    null);
            Fetch.fetch(runtime, transport, request);
            sourceBody[0] = 9;
        });

        okhttp3.Request mapped = factory.lastCall.request();
        assertEquals("https://example.test/request", mapped.url().toString(), "request URL");
        assertEquals("POST", mapped.method(), "request method");
        assertEquals(2, mapped.headers().size(), "duplicate header count");
        assertEquals("x-test", mapped.headers().name(0), "normalized first header name");
        assertEquals("one", mapped.headers().value(0), "first header value");
        assertEquals("x-test", mapped.headers().name(1), "normalized second header name");
        assertEquals("two", mapped.headers().value(1), "second header value");
        assertArrayEquals(new byte[] {1, 2, 3}, mapped.body().copyBytes(), "request body snapshot");

        factory.lastCall.respond(response(
                factory.lastCall,
                204,
                "No Content",
                new ResponseBody(new byte[0])));
        fixture.runtime.close();
        assertTrue(factory.lastCall.cancelled, "closing queued Fetch cancels OkHttp call");
        pass();
    }

    private void testBodylessRequiredMethodsUseAnEmptyBody() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingFactory factory = new RecordingFactory();
        OkHttpFetchTransport transport = new OkHttpFetchTransport(factory);

        runTurn(fixture.runtime, runtime -> Fetch.fetch(
                runtime,
                transport,
                new Request(runtime, "https://example.test/post", "POST", null, null, null)));
        assertEmptyRequiredBody(factory.lastCall, "POST");
        factory.lastCall.respond(response(
                factory.lastCall,
                204,
                "No Content",
                new ResponseBody(new byte[0])));
        fixture.executor.runNext();

        runTurn(fixture.runtime, runtime -> Fetch.fetch(
                runtime,
                transport,
                new Request(runtime, "https://example.test/query", "QUERY", null, null, null)));
        assertEmptyRequiredBody(factory.lastCall, "QUERY");
        factory.lastCall.respond(response(
                factory.lastCall,
                204,
                "No Content",
                new ResponseBody(new byte[0])));
        fixture.executor.runNext();

        fixture.runtime.close();
        pass();
    }

    private static void assertEmptyRequiredBody(FakeCall call, String method) {
        RequestBody body = call.request().body();
        assertTrue(body != null, "OkHttp receives a body for body-required " + method);
        assertTrue(body == RequestBody.EMPTY, "bodyless " + method + " reuses OkHttp's empty body");
        assertEquals(0, body.copyBytes().length, "bodyless " + method + " maps to zero bytes");
    }

    private void testForeignResponseIsBufferedClosedAndDeliveredOnOwner() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingFactory factory = new RecordingFactory();
        OkHttpFetchTransport transport = new OkHttpFetchTransport(factory);
        JsPromise[] fetchPromise = new JsPromise[1];

        runTurn(fixture.runtime, runtime ->
                fetchPromise[0] = Fetch.fetch(runtime, transport, "https://example.test/start"));

        ResponseBody body = new ResponseBody("payload".getBytes(StandardCharsets.UTF_8));
        okhttp3.Response rawResponse = redirectedResponse(factory.lastCall, 201, "Created", body);
        AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                factory.lastCall.respond(rawResponse);
            } catch (Throwable error) {
                workerFailure.set(error);
            }
        }, "okhttp-callback");
        worker.start();
        worker.join();
        if (workerFailure.get() != null) {
            throw new AssertionError("foreign OkHttp callback failed", workerFailure.get());
        }

        assertTrue(body.isClosed(), "ResponseBody is closed on callback thread");
        assertTrue(body.getReadThread() == worker, "ResponseBody is read on callback thread");
        assertTrue(body.getCloseThread() == worker, "ResponseBody closes on callback thread");
        assertTrue(rawResponse.isClosed(), "OkHttp Response is closed on callback thread");
        assertTrue(rawResponse.getCloseThread() == worker, "Response closes on callback thread");
        assertTrue(fetchPromise[0].isPending(), "foreign callback cannot settle Fetch Promise");
        assertEquals(1, fixture.executor.getPendingCallbackCount(), "one owner admission");

        fixture.executor.runNext();
        JsPromise[] bodyPromise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            Response response = (Response) fetchPromise[0].getReferencePayload();
            assertEquals(201, response.getStatus(), "response status");
            assertEquals("Created", response.getStatusText(), "response status text");
            assertEquals("https://example.test/final", response.getUrl(), "final response URL");
            assertTrue(response.isRedirected(), "prior response marks redirect");
            assertEquals("one, two", response.getHeaders().get("x-duplicate"), "duplicate response headers");
            bodyPromise[0] = response.arrayBuffer();
        });
        assertArrayEquals(
                "payload".getBytes(StandardCharsets.UTF_8),
                (byte[]) bodyPromise[0].getReferencePayload(),
                "buffered response bytes");

        fixture.runtime.close();
        pass();
    }

    private void testAuthorizationFollowUpIsNotReportedAsRedirect() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingFactory factory = new RecordingFactory();
        OkHttpFetchTransport transport = new OkHttpFetchTransport(factory);
        JsPromise[] promise = new JsPromise[1];

        runTurn(fixture.runtime, runtime ->
                promise[0] = Fetch.fetch(runtime, transport, "https://example.test/auth"));

        okhttp3.Response challenge = new okhttp3.Response.Builder()
                .request(factory.lastCall.request())
                .code(401)
                .message("Unauthorized")
                .build();
        okhttp3.Response completed = new okhttp3.Response.Builder()
                .request(factory.lastCall.request())
                .code(200)
                .message("OK")
                .body(new ResponseBody(new byte[0]))
                .priorResponse(challenge)
                .build();
        factory.lastCall.respond(completed);
        fixture.executor.runNext();

        Response response = (Response) promise[0].getReferencePayload();
        runTurn(fixture.runtime, runtime ->
                assertTrue(!response.isRedirected(), "authorization retry is not a redirect"));

        fixture.runtime.close();
        pass();
    }

    private void testBodyReadFailureBecomesNetworkErrorAndClosesResponse() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingFactory factory = new RecordingFactory();
        OkHttpFetchTransport transport = new OkHttpFetchTransport(factory);
        JsPromise[] promise = new JsPromise[1];

        runTurn(fixture.runtime, runtime ->
                promise[0] = Fetch.fetch(runtime, transport, "https://example.test/failure"));

        IOException readFailure = new IOException("body read failed");
        ResponseBody body = new ResponseBody(null, readFailure);
        okhttp3.Response rawResponse = response(factory.lastCall, 200, "OK", body);
        factory.lastCall.respond(rawResponse);

        assertTrue(body.isClosed(), "failed ResponseBody is closed");
        assertTrue(rawResponse.isClosed(), "failed Response is closed");
        fixture.executor.runNext();
        assertTrue(promise[0].isRejected(), "body read failure rejects Fetch");
        Object rejection = promise[0].getReferencePayload();
        assertTrue(rejection instanceof JsTypeError, "transport failure becomes TypeError");
        assertTrue(((JsTypeError) rejection).getCause() == readFailure, "I/O cause is retained");

        fixture.runtime.close();
        pass();
    }

    private void testAbortCancelsTheExactOkHttpCall() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingFactory factory = new RecordingFactory();
        OkHttpFetchTransport transport = new OkHttpFetchTransport(factory);
        AbortController[] controller = new AbortController[1];
        JsPromise[] promise = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            controller[0] = new AbortController(runtime);
            Request request = new Request(
                    runtime,
                    "https://example.test/abort",
                    "GET",
                    null,
                    null,
                    controller[0].getSignal());
            promise[0] = Fetch.fetch(runtime, transport, request);
            controller[0].abortBoolean(true);
        });

        assertTrue(factory.lastCall.cancelled, "AbortSignal cancels the exact OkHttp call");
        factory.lastCall.fail(new IOException("late canceled callback"));
        assertEquals(1, fixture.executor.getPendingCallbackCount(), "late callback does not add delivery");
        fixture.executor.runNext();
        assertTrue(promise[0].isRejected(), "aborted Fetch rejects");
        assertTrue(promise[0].getBooleanPayload(), "exact abort reason is preserved by core");

        fixture.runtime.close();
        pass();
    }

    private void testUnsupportedFinalStatusBecomesNetworkError() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingFactory factory = new RecordingFactory();
        OkHttpFetchTransport transport = new OkHttpFetchTransport(factory);
        JsPromise[] promise = new JsPromise[1];

        runTurn(fixture.runtime, runtime ->
                promise[0] = Fetch.fetch(runtime, transport, "https://example.test/status"));

        ResponseBody body = new ResponseBody(new byte[0]);
        okhttp3.Response rawResponse = response(factory.lastCall, 101, "Switching Protocols", body);
        factory.lastCall.respond(rawResponse);
        assertTrue(rawResponse.isClosed(), "unsupported response is closed");
        fixture.executor.runNext();
        assertTrue(promise[0].isRejected(), "unsupported final status rejects Fetch");
        Object rejection = promise[0].getReferencePayload();
        assertTrue(rejection instanceof JsTypeError, "unsupported status becomes network TypeError");
        assertTrue(((JsTypeError) rejection).getCause() instanceof IOException, "status failure retains I/O cause");

        fixture.runtime.close();
        pass();
    }

    private void pass() {
        passed++;
    }
}
