package io.github.akisarou.jvmwww.web.bodies.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.bodies.File;
import io.github.akisarou.jvmwww.web.bodies.FormData;
import io.github.akisarou.jvmwww.web.bodies.FormDataParser;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Direct conformance for bounded byte-oriented FormData parsers. */
public final class FormDataParserConformance {
    private static final String BOUNDARY = "jvmwwwParserBoundary0123456789";
    private int passed;

    public static void main(String[] args) throws Throwable {
        new FormDataParserConformance().run();
    }

    private void run() throws Throwable {
        testUrlEncodedParser();
        testMultipartParserAndFileView();
        testMultipartSerializerRoundTripEscapes();
        testMalformedInputAndLimits();
        testOwnerConfinement();
        System.out.println("FormData parser conformance: " + passed + " tests passed");
    }

    private void testUrlEncodedParser() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            BufferedBodySnapshot snapshot = BufferedBodySnapshot.copyOf(
                    "a=1&a=2&space=x+y&utf=%F0%9F%92%A9"
                            .getBytes(StandardCharsets.US_ASCII),
                    "application/x-www-form-urlencoded");
            FormData data = FormDataParser.parseUrlEncoded(runtime, snapshot);
            assertEquals(4, data.size(), "URL-encoded entry count");
            assertEquals("1", data.getStringValue(0), "first duplicate");
            assertEquals("2", data.getStringValue(1), "second duplicate");
            assertEquals("x y", data.get("space"), "plus decoding");
            assertEquals("\ud83d\udca9", data.get("utf"), "UTF-8 decoding");
        });
        fixture.runtime.close();
        pass();
    }

    private void testMultipartParserAndFileView() throws Throwable {
        Fixture fixture = new Fixture();
        byte[] source = multipart(
                part("text", null, null, "hello".getBytes(StandardCharsets.UTF_8)),
                part("file", "x.bin", "application/octet-stream", new byte[] {0, 1, (byte) 0xff}));
        BufferedBodySnapshot[] snapshot = new BufferedBodySnapshot[1];
        FormData[] parsed = new FormData[1];
        runTurn(fixture.runtime, runtime -> {
            snapshot[0] = BufferedBodySnapshot.copyOf(source, null);
            source[0] = 'x';
            parsed[0] = FormDataParser.parseMultipart(runtime, snapshot[0], BOUNDARY);
            assertEquals("hello", parsed[0].getStringValue(0), "multipart text");
            File file = parsed[0].getFileValue(1);
            assertEquals("x.bin", file.getName(), "file name");
            assertEquals("application/octet-stream", file.getType(), "file type");
        });
        JsPromise[] bytes = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> bytes[0] = parsed[0].getFileValue(1).bytes());
        assertArrayEquals(
                new byte[] {0, 1, (byte) 0xff},
                (byte[]) bytes[0].getReferencePayload(),
                "shared immutable file range");
        fixture.runtime.close();
        pass();
    }

    private void testMultipartSerializerRoundTripEscapes() throws Throwable {
        Fixture fixture = new Fixture();
        FormData[] parsed = new FormData[1];
        runTurn(fixture.runtime, runtime -> {
            FormData source = new FormData(runtime, () -> BOUNDARY);
            source.append("n\r\"\\%41", "v\nx");
            source.append(
                    "file",
                    new File(
                            runtime,
                            new byte[] {7},
                            "f\r\"\\%41",
                            "application/octet-stream",
                            1L));
            BufferedBodySnapshot snapshot = source.snapshot();
            parsed[0] = FormDataParser.parseMultipart(runtime, snapshot, BOUNDARY);
            assertEquals("n\r\n\"\\%41", parsed[0].getName(0),
                    "name reverses only HTML multipart escapes");
            assertEquals("v\r\nx", parsed[0].getStringValue(0),
                    "string values retain serializer CRLF normalization");
            assertEquals("f\r\"\\%41", parsed[0].getFileValue(1).getName(),
                    "filename quote/newline escapes decode while literal percent/backslash survive");
        });
        fixture.runtime.close();
        pass();
    }

    private void testMalformedInputAndLimits() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            assertThrows(
                    JsTypeError.class,
                    () -> FormDataParser.parseMultipart(
                            runtime,
                            BufferedBodySnapshot.copyOf(new byte[0], null),
                            ""),
                    "empty boundary");
            assertThrows(
                    JsTypeError.class,
                    () -> FormDataParser.parseMultipart(
                            runtime,
                            BufferedBodySnapshot.copyOf(
                                    ("--" + BOUNDARY + "\r\nX: y\r\n\r\nv\r\n--"
                                            + BOUNDARY + "--\r\n")
                                            .getBytes(StandardCharsets.ISO_8859_1),
                                    null),
                            BOUNDARY),
                    "missing Content-Disposition");
            assertThrows(
                    JsTypeError.class,
                    () -> FormDataParser.parseMultipart(
                            runtime,
                            BufferedBodySnapshot.copyOf(
                                    ("--" + BOUNDARY + "\r\n"
                                            + "Content-Disposition: form-data; name=\"x\"\r\n"
                                            + "Content-Disposition: form-data; name=\"y\"\r\n"
                                            + "\r\nv\r\n--" + BOUNDARY + "--\r\n")
                                            .getBytes(StandardCharsets.ISO_8859_1),
                                    null),
                            BOUNDARY),
                    "duplicate Content-Disposition");
            assertThrows(
                    JsTypeError.class,
                    () -> FormDataParser.parseMultipart(
                            runtime,
                            BufferedBodySnapshot.copyOf(
                                    ("--" + BOUNDARY + "\r\n"
                                            + "Content-Disposition: form-data; name=\"x\"; "
                                            + "filename*=UTF-8''x.txt\r\n\r\nv\r\n--"
                                            + BOUNDARY + "--\r\n")
                                            .getBytes(StandardCharsets.ISO_8859_1),
                                    null),
                            BOUNDARY),
                    "extended filename parameter");
            assertThrows(
                    JsTypeError.class,
                    () -> FormDataParser.parseMultipart(
                            runtime,
                            BufferedBodySnapshot.copyOf(
                                    ("--" + BOUNDARY + "\n"
                                            + "Content-Disposition: form-data; name=\"x\"\n\n"
                                            + "v\n--" + BOUNDARY + "--\n")
                                            .getBytes(StandardCharsets.ISO_8859_1),
                                    null),
                            BOUNDARY),
                    "LF-only framing");
            assertThrows(
                    JsTypeError.class,
                    () -> FormDataParser.parseMultipart(
                            runtime,
                            BufferedBodySnapshot.copyOf(new byte[0], null),
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    "boundary length limit");

            StringBuilder many = new StringBuilder(128 * 1025);
            for (int index = 0; index < 1025; index++) {
                many.append("--").append(BOUNDARY).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"x\"\r\n\r\n")
                        .append("v\r\n");
            }
            many.append("--").append(BOUNDARY).append("--\r\n");
            assertThrows(
                    JsTypeError.class,
                    () -> FormDataParser.parseMultipart(
                            runtime,
                            BufferedBodySnapshot.copyOf(
                                    many.toString().getBytes(StandardCharsets.ISO_8859_1),
                                    null),
                            BOUNDARY),
                    "part-count limit");
        });
        fixture.runtime.close();
        pass();
    }

    private void testOwnerConfinement() throws Throwable {
        Fixture fixture = new Fixture();
        FormData[] data = new FormData[1];
        runTurn(fixture.runtime, runtime -> data[0] = FormDataParser.parseUrlEncoded(
                runtime,
                BufferedBodySnapshot.copyOf(new byte[] {'a', '=', '1'}, null)));
        assertThrows(IllegalStateException.class, data[0]::size, "parsed result owner confinement");
        fixture.runtime.close();
        pass();
    }

    private static byte[] multipart(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] closing = ("--" + BOUNDARY + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] output = new byte[length + closing.length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, output, offset, part.length);
            offset += part.length;
        }
        System.arraycopy(closing, 0, output, offset, closing.length);
        return output;
    }

    private static byte[] part(String name, String filename, String type, byte[] value) {
        StringBuilder header = new StringBuilder(128);
        header.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"")
                .append(name).append('"');
        if (filename != null) header.append("; filename=\"").append(filename).append('"');
        header.append("\r\n");
        if (type != null) header.append("Content-Type: ").append(type).append("\r\n");
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
}
