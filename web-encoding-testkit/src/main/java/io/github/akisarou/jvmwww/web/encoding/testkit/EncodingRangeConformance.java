package io.github.akisarou.jvmwww.web.encoding.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.encoding.TextDecoder;

/** Focused conformance for allocation-free immutable byte-range decoding. */
public final class EncodingRangeConformance {
    private EncodingRangeConformance() {}

    public static void main(String[] args) throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            TextDecoder decoder = new TextDecoder(runtime, "utf-8", false, true);
            byte[] input = new byte[] {
                'x', (byte) 0xf0, (byte) 0x9f, (byte) 0x92, (byte) 0xa9, 'y'
            };
            assertEquals("\ud83d\udca9", decoder.decode(input, 1, 4), "selected range");
            assertEquals("", decoder.decode(input, input.length, 0), "empty tail range");
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> decoder.decode(input, -1, 1),
                    "negative range");
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> decoder.decode(input, 4, 3),
                    "overflowing range");
        });
        fixture.runtime.close();
        System.out.println("Web encoding range conformance: 1 test passed");
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try {
            task.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    private static void assertEquals(String expected, Object actual, String label) {
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
}
