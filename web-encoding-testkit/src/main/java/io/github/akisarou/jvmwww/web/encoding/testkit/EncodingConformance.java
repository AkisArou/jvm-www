package io.github.akisarou.jvmwww.web.encoding.testkit;

import io.github.akisarou.jvmwww.runtime.JsRangeError;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.encoding.TextDecoder;
import io.github.akisarou.jvmwww.web.encoding.TextDecoderOptions;
import io.github.akisarou.jvmwww.web.encoding.TextEncoder;
import io.github.akisarou.jvmwww.web.encoding.TextEncoderEncodeIntoResult;
import java.util.Arrays;

/** Deterministic conformance for the selected WHATWG UTF-8 Encoding API profile. */
public final class EncodingConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new EncodingConformance().run();
    }

    private void run() throws Throwable {
        testEncoderScalarConversion();
        testEncodeIntoProgress();
        testDecoderLabelsAndOptions();
        testDecoderReplacementAndFatal();
        testBomHandling();
        testStreamingSplitSequences();
        testStreamingErrorsAndFlush();
        testOwnerConfinement();
        System.out.println("Web encoding conformance: " + passed + " tests passed");
    }

    private void testEncoderScalarConversion() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            TextEncoder encoder = new TextEncoder(runtime);
            assertEquals("utf-8", encoder.getEncoding(), "encoder name");
            assertByteArrayEquals(
                    new byte[] {
                        0x41,
                        (byte) 0xc2, (byte) 0xa2,
                        (byte) 0xe2, (byte) 0x82, (byte) 0xac,
                        (byte) 0xf0, (byte) 0x9f, (byte) 0x92, (byte) 0xa9,
                        (byte) 0xef, (byte) 0xbf, (byte) 0xbd,
                        0x58,
                        (byte) 0xef, (byte) 0xbf, (byte) 0xbd
                    },
                    encoder.encode("A\u00a2\u20ac\ud83d\udca9\ud800X\udc00"),
                    "UTF-16 scalar conversion");
            assertEquals(0, encoder.encode().length, "default empty input");
        });
        fixture.runtime.close();
        pass();
    }

    private void testEncodeIntoProgress() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            TextEncoder encoder = new TextEncoder(runtime);
            byte[] shortDestination = new byte[4];
            TextEncoderEncodeIntoResult shortProgress =
                    encoder.encodeInto("A\ud83d\udca9B", shortDestination);
            assertEquals(1L, shortProgress.getRead(), "partial read code units");
            assertEquals(1L, shortProgress.getWritten(), "partial written bytes");
            assertEquals(0x41, shortDestination[0] & 0xff, "ASCII prefix written");

            byte[] exactDestination = new byte[5];
            TextEncoderEncodeIntoResult exactProgress =
                    encoder.encodeInto("A\ud83d\udca9B", exactDestination);
            assertEquals(3L, exactProgress.getRead(), "pair counts as two code units");
            assertEquals(5L, exactProgress.getWritten(), "complete scalar written");
            assertByteArrayEquals(
                    new byte[] {
                        0x41, (byte) 0xf0, (byte) 0x9f, (byte) 0x92, (byte) 0xa9
                    },
                    exactDestination,
                    "encodeInto bytes");

            TextEncoderEncodeIntoResult replacement =
                    encoder.encodeInto("\ud800", new byte[2]);
            assertEquals(0L, replacement.getRead(), "replacement is not partially consumed");
            assertEquals(0L, replacement.getWritten(), "replacement is not partially written");
        });
        fixture.runtime.close();
        pass();
    }

    private void testDecoderLabelsAndOptions() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            TextDecoder decoder = new TextDecoder(runtime, "\tUtF-8\r");
            assertEquals("utf-8", decoder.getEncoding(), "canonical decoder name");
            assertTrue(!decoder.isFatal(), "replacement is default");
            assertTrue(!decoder.isIgnoreBOM(), "BOM stripping is default");

            TextDecoder configured =
                    new TextDecoder(
                            runtime,
                            "unicode-1-1-utf-8",
                            new TextDecoderOptions(true, true));
            assertTrue(configured.isFatal(), "fatal option");
            assertTrue(configured.isIgnoreBOM(), "ignoreBOM option");
            new TextDecoder(runtime, "utf8");

            assertThrows(
                    JsRangeError.class,
                    () -> new TextDecoder(runtime, "windows-1252"),
                    "unsupported label is a RangeError");
            assertThrows(
                    JsRangeError.class,
                    () -> new TextDecoder(runtime, "\u000butf-8"),
                    "only ASCII whitespace is stripped");
        });
        fixture.runtime.close();
        pass();
    }

    private void testDecoderReplacementAndFatal() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            TextDecoder decoder = new TextDecoder(runtime);
            assertEquals(
                    "\ud83d\udca9",
                    decoder.decode(
                            new byte[] {
                                (byte) 0xf0, (byte) 0x9f, (byte) 0x92, (byte) 0xa9
                            }),
                    "valid supplementary scalar");
            assertEquals(
                    "\ufffd(\ufffd",
                    decoder.decode(new byte[] {(byte) 0xe2, 0x28, (byte) 0xa1}),
                    "invalid continuation is restored");
            assertEquals(
                    "\ufffd\ufffd\ufffd\ufffd",
                    decoder.decode(
                            new byte[] {
                                (byte) 0xf0, (byte) 0x80, (byte) 0x80, (byte) 0x80
                            }),
                    "overlong sequence replacement boundaries");

            TextDecoder fatal = new TextDecoder(runtime, "utf-8", true, false);
            assertThrows(
                    JsTypeError.class,
                    () -> fatal.decode(new byte[] {(byte) 0xff}),
                    "fatal decode");
            assertEquals("A", fatal.decode(new byte[] {0x41}, true), "fatal decoder reuse");
            assertEquals("", fatal.decode(), "fatal decoder flush after reuse");
        });
        fixture.runtime.close();
        pass();
    }

    private void testBomHandling() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            byte[] bomThenA = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 0x41};
            TextDecoder decoder = new TextDecoder(runtime);
            assertEquals("A", decoder.decode(bomThenA), "initial BOM stripped");
            assertEquals("A", decoder.decode(bomThenA), "BOM state resets for a new decode");
            assertEquals(
                    "\ufeffA",
                    decoder.decode(
                            new byte[] {
                                (byte) 0xef, (byte) 0xbb, (byte) 0xbf,
                                (byte) 0xef, (byte) 0xbb, (byte) 0xbf,
                                0x41
                            }),
                    "only the first BOM is stripped");

            TextDecoder ignore = new TextDecoder(runtime, "utf-8", false, true);
            assertEquals("\ufeffA", ignore.decode(bomThenA), "ignoreBOM preserves BOM");
        });
        fixture.runtime.close();
        pass();
    }

    private void testStreamingSplitSequences() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            TextDecoder decoder = new TextDecoder(runtime);
            assertEquals(
                    "",
                    decoder.decode(new byte[] {(byte) 0xf0, (byte) 0x9f}, true),
                    "split scalar prefix buffered");
            assertEquals(
                    "",
                    decoder.decode(new byte[] {(byte) 0x92}, true),
                    "split scalar middle buffered");
            assertEquals(
                    "\ud83d\udca9",
                    decoder.decode(new byte[] {(byte) 0xa9}, true),
                    "split scalar completed");
            assertEquals("", decoder.decode(), "clean streaming flush");

            TextDecoder splitBom = new TextDecoder(runtime);
            assertEquals("", splitBom.decode(new byte[] {(byte) 0xef}, true), "BOM byte one");
            assertEquals("", splitBom.decode(new byte[] {(byte) 0xbb}, true), "BOM byte two");
            assertEquals(
                    "A",
                    splitBom.decode(new byte[] {(byte) 0xbf, 0x41}, true),
                    "split BOM stripped once complete");
            assertEquals("", splitBom.decode(), "split BOM flush");
        });
        fixture.runtime.close();
        pass();
    }

    private void testStreamingErrorsAndFlush() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            TextDecoder decoder = new TextDecoder(runtime);
            assertEquals("", decoder.decode(new byte[] {(byte) 0xe2}, true), "lead buffered");
            assertEquals(
                    "\ufffd(",
                    decoder.decode(new byte[] {0x28}, true),
                    "invalid continuation reprocessed");
            assertEquals("\ufffd", decoder.decode(new byte[] {(byte) 0xa1}, true), "stray continuation");
            assertEquals("", decoder.decode(), "error stream flush");

            TextDecoder incomplete = new TextDecoder(runtime);
            assertEquals(
                    "",
                    incomplete.decode(new byte[] {(byte) 0xe2, (byte) 0x82}, true),
                    "incomplete sequence buffered");
            assertEquals("\ufffd", incomplete.decode(), "incomplete sequence flush replacement");

            TextDecoder fatal = new TextDecoder(runtime, "utf-8", true, false);
            assertEquals("", fatal.decode(new byte[] {(byte) 0xe2}, true), "fatal lead buffered");
            assertThrows(JsTypeError.class, fatal::decode, "fatal incomplete flush");
        });
        fixture.runtime.close();
        pass();
    }

    private void testOwnerConfinement() throws Throwable {
        Fixture fixture = new Fixture();
        TextEncoder[] encoder = new TextEncoder[1];
        runTurn(fixture.runtime, runtime -> encoder[0] = new TextEncoder(runtime));
        assertThrows(
                IllegalStateException.class,
                () -> encoder[0].encode("outside"),
                "encoding outside a host turn");
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

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(String expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertByteArrayEquals(byte[] expected, byte[] actual, String label) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(
                    label
                            + ": expected "
                            + Arrays.toString(expected)
                            + ", got "
                            + Arrays.toString(actual));
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
