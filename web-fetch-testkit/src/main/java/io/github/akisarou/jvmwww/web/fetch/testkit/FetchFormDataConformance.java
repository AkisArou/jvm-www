package io.github.akisarou.jvmwww.web.fetch.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.bodies.File;
import io.github.akisarou.jvmwww.web.bodies.FormData;
import io.github.akisarou.jvmwww.web.fetch.Fetch;
import io.github.akisarou.jvmwww.web.fetch.FetchTransport;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCall;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCallback;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportResponse;
import io.github.akisarou.jvmwww.web.fetch.Response;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Deterministic conformance for bounded Response.formData() parsing. */
public final class FetchFormDataConformance {
    private static final String BOUNDARY = "jvmwwwResponseBoundary0123456789";
    private int passed;

    public static void main(String[] args) throws Throwable {
        new FetchFormDataConformance().run();
    }

    private void run() throws Throwable {
        testUrlEncodedParsingAndOneShotState();
        testMultipartTextAndFileParsing();
        testMultipartDefaultsAndOrdering();
        testUnsupportedAndMalformedBodiesReject();
        testMultipartPartLimit();
        testQuotedBoundaryAndRuntimeOwnership();
        System.out.println("Fetch formData conformance: " + passed + " tests passed");
    }

    private void testUrlEncodedParsingAndOneShotState() throws Throwable {
        Fixture fixture = new Fixture();
        Response response = fetchResponse(
                fixture,
                new String[] {"content-type"},
                new String[] {"application/x-www-form-urlencoded;charset=windows-1252"},
                "a=1&a=2&space=x+y&utf=%F0%9F%92%A9&bom=%EF%BB%BFz"
                        .getBytes(StandardCharsets.US_ASCII));
        JsPromise[] parsed = new JsPromise[1];
        JsPromise[] second = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            parsed[0] = response.formData();
            assertTrue(response.isBodyUsed(), "formData marks body used synchronously");
            second[0] = response.text();
            assertTrue(parsed[0].isPending(), "formData settles at the microtask checkpoint");
        });
        assertTrue(parsed[0].isFulfilled(), "URL-encoded formData fulfills");
        assertTrue(second[0].isRejected(), "second body consumer rejects");
        FormData data = (FormData) parsed[0].getReferencePayload();
        runTurn(fixture.runtime, runtime -> {
            assertEquals(5, data.size(), "ordered URL-encoded entry count");
            assertEquals("1", data.getStringValue(0), "first duplicate");
            assertEquals("2", data.getStringValue(1), "second duplicate");
            assertEquals("x y", data.get("space"), "plus decoding");
            assertEquals("\ud83d\udca9", data.get("utf"), "UTF-8 scalar decoding");
            assertEquals("\ufeffz", data.get("bom"), "URL form decode preserves BOM");
        });
        fixture.runtime.close();
        pass();
    }

    private void testMultipartTextAndFileParsing() throws Throwable {
        Fixture fixture = new Fixture();
        byte[] body = multipart(
                part("text", null, null, new byte[] {
                    (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'h', 'i'
                }),
                part("upload", "x.bin", "application/octet-stream", new byte[] {
                    0, 1, 2, (byte) 0xff
                }));
        Response response = fetchResponse(
                fixture,
                new String[] {"content-type"},
                new String[] {"multipart/form-data; boundary=" + BOUNDARY},
                body);
        JsPromise[] parsed = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> parsed[0] = response.formData());
        FormData data = (FormData) parsed[0].getReferencePayload();
        File[] file = new File[1];
        JsPromise[] bytes = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            assertEquals(2, data.size(), "multipart entry count");
            assertEquals("\ufeffhi", data.getStringValue(0), "text uses UTF-8 decode without BOM");
            file[0] = data.getFileValue(1);
            assertEquals("x.bin", file[0].getName(), "multipart filename");
            assertEquals("application/octet-stream", file[0].getType(), "multipart file type");
            bytes[0] = file[0].bytes();
        });
        assertArrayEquals(
                new byte[] {0, 1, 2, (byte) 0xff},
                (byte[]) bytes[0].getReferencePayload(),
                "multipart file bytes");
        fixture.runtime.close();
        pass();
    }

    private void testMultipartDefaultsAndOrdering() throws Throwable {
        Fixture fixture = new Fixture();
        byte[] body = multipart(
                part("a", null, null, "one".getBytes(StandardCharsets.UTF_8)),
                part("a", null, "text/ignored;charset=iso-8859-1",
                        "two".getBytes(StandardCharsets.UTF_8)),
                part("empty-file", "", null, new byte[0]));
        Response response = fetchResponse(
                fixture,
                new String[] {"content-type"},
                new String[] {"multipart/form-data; boundary=\"" + BOUNDARY + "\""},
                body);
        JsPromise[] parsed = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> parsed[0] = response.formData());
        FormData data = (FormData) parsed[0].getReferencePayload();
        runTurn(fixture.runtime, runtime -> {
            assertEquals(3, data.size(), "multipart preserves order and duplicates");
            assertEquals("one", data.getStringValue(0), "first text value");
            assertEquals("two", data.getStringValue(1), "text ignores part Content-Type");
            File file = data.getFileValue(2);
            assertEquals("", file.getName(), "empty filename remains a File");
            assertEquals("text/plain", file.getType(), "missing file Content-Type default");
        });
        fixture.runtime.close();
        pass();
    }

    private void testUnsupportedAndMalformedBodiesReject() throws Throwable {
        assertFormDataRejects(
                new String[] {"content-type"},
                new String[] {"text/plain"},
                new byte[0],
                "unsupported MIME type");
        assertFormDataRejects(
                new String[] {"content-type"},
                new String[] {"multipart/form-data"},
                new byte[0],
                "missing multipart boundary");
        assertFormDataRejects(
                new String[] {"content-type"},
                new String[] {"multipart/form-data; boundary=" + BOUNDARY},
                ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"x\"\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1),
                "unterminated multipart headers");
        pass();
    }

    private void testMultipartPartLimit() throws Throwable {
        StringBuilder body = new StringBuilder(128 * 1025);
        for (int index = 0; index < 1025; index++) {
            body.append("--").append(BOUNDARY).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"x\"\r\n\r\n")
                    .append("v\r\n");
        }
        body.append("--").append(BOUNDARY).append("--\r\n");
        assertFormDataRejects(
                new String[] {"content-type"},
                new String[] {"multipart/form-data; boundary=" + BOUNDARY},
                body.toString().getBytes(StandardCharsets.ISO_8859_1),
                "multipart part limit");
        pass();
    }

    private void testQuotedBoundaryAndRuntimeOwnership() throws Throwable {
        Fixture fixture = new Fixture();
        Response response = fetchResponse(
                fixture,
                new String[] {"content-type"},
                new String[] {"multipart/form-data; boundary=\"" + BOUNDARY + "\""},
                multipart(part("x", null, null, new byte[] {'y'})));
        JsPromise[] parsed = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> parsed[0] = response.formData());
        FormData data = (FormData) parsed[0].getReferencePayload();
        assertThrows(
                IllegalStateException.class,
                data::size,
                "parsed FormData remains owner-confined");
        runTurn(fixture.runtime, runtime -> assertEquals("y", data.get("x"), "quoted boundary"));
        fixture.runtime.close();
        pass();
    }

    private static Response fetchResponse(
            Fixture fixture,
            String[] headerNames,
            String[] headerValues,
            byte[] body) throws Throwable {
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
                headerNames,
                headerValues,
                body,
                false));
        fixture.executor.runNext();
        return (Response) fetch[0].getReferencePayload();
    }

    private static void assertFormDataRejects(
            String[] headerNames,
            String[] headerValues,
            byte[] body,
            String label) throws Throwable {
        Fixture fixture = new Fixture();
        Response response = fetchResponse(fixture, headerNames, headerValues, body);
        JsPromise[] parsed = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> parsed[0] = response.formData());
        assertTrue(parsed[0].isRejected(), label + " rejects");
        assertTrue(
                parsed[0].getReferencePayload() instanceof JsTypeError,
                label + " rejects with TypeError");
        fixture.runtime.close();
    }

    private static byte[] multipart(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] end = ("--" + BOUNDARY + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] output = new byte[length + end.length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, output, offset, part.length);
            offset += part.length;
        }
        System.arraycopy(end, 0, output, offset, end.length);
        return output;
    }

    private static byte[] part(
            String name,
            String filename,
            String contentType,
            byte[] value) {
        StringBuilder header = new StringBuilder(128);
        header.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"")
                .append(name).append('"');
        if (filename != null) header.append("; filename=\"").append(filename).append('"');
        header.append("\r\n");
        if (contentType != null) header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("\r\n");
        byte[] prefix = header.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] output = new byte[prefix.length + value.length + 2];
        System.arraycopy(prefix, 0, output, 0, prefix.length);
        System.arraycopy(value, 0, output, prefix.length, value.length);
        output[output.length - 2] = '\r';
        output[output.length - 1] = '\n';
        return output;
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

    private static final class RecordingTransport implements FetchTransport {
        FetchTransportCallback callback;

        @Override
        public FetchTransportCall start(
                FetchTransportRequest request,
                FetchTransportCallback callback) {
            this.callback = callback;
            return new FetchTransportCall() {
                @Override public void cancel() {}
            };
        }
    }
}
