package io.github.akisarou.jvmwww.web.timing.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.timing.AnimationFrameScheduler;
import io.github.akisarou.jvmwww.web.timing.Performance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic Java 8 conformance for Performance and requestAnimationFrame. */
public final class AnimationFrameConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new AnimationFrameConformance().run();
    }

    private void run() throws Throwable {
        testPerformanceOriginAndMonotonicity();
        testOneHostRequestFifoAndOneCheckpoint();
        testCancellationBeforeAndDuringFrame();
        testRequestsDuringFrameWaitForNextFrame();
        testHandleGenerationAndStaleCancellation();
        testCallbackFailureReportsAndContinues();
        testHostRequestFailureRollsBack();
        testRuntimeCloseCancelsPendingFrame();
        testDeliveredFrameReleasesRuntimeOwnership();
        testOwnerConfinementAndRuntimeIsolation();
        System.out.println("Web timing conformance: " + passed + " tests passed");
    }

    private void testPerformanceOriginAndMonotonicity() throws Throwable {
        Fixture fixture = new Fixture();
        fixture.host.setNowNanos(9_000_000_000L);
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        runTurn(fixture.runtime, runtime -> {
            scheduler[0] = new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            Performance performance = scheduler[0].getPerformance();
            assertEquals(0.0, performance.now(), "initial performance.now");
            fixture.host.advanceNanos(1_500_000L);
            assertEquals(1.5, performance.now(), "relative milliseconds");
            fixture.host.setNowNanos(9_001_000_000L);
            assertThrows(IllegalStateException.class, performance::now, "backwards clock rejected");
        });
        fixture.runtime.close();
        pass();
    }

    private void testOneHostRequestFifoAndOneCheckpoint() throws Throwable {
        Fixture fixture = new Fixture();
        fixture.host.setNowNanos(100_000_000L);
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        List<String> trace = new ArrayList<String>();
        List<Double> timestamps = new ArrayList<Double>();
        runTurn(fixture.runtime, runtime -> {
            scheduler[0] = new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            for (int index = 0; index < 20; index++) {
                final int value = index;
                scheduler[0].requestAnimationFrame(timestamp -> {
                    trace.add("frame-" + value);
                    timestamps.add(Double.valueOf(timestamp));
                    runtime.queueMicrotask(owner -> trace.add("micro-" + value));
                });
            }
            assertEquals(1, fixture.host.getRequestCount(), "one coalesced host frame");
        });

        fixture.host.fireAt(116_000_000L);
        assertEquals(40, trace.size(), "callback and microtask count");
        for (int index = 0; index < 20; index++) {
            assertEquals("frame-" + index, trace.get(index), "FIFO frame callback " + index);
            assertEquals(16.0, timestamps.get(index).doubleValue(), "shared frame timestamp");
        }
        for (int index = 0; index < 20; index++) {
            assertEquals("micro-" + index, trace.get(20 + index), "single final checkpoint " + index);
        }
        fixture.runtime.close();
        pass();
    }

    private void testCancellationBeforeAndDuringFrame() throws Throwable {
        Fixture fixture = new Fixture();
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        List<String> trace = new ArrayList<String>();
        double[] secondHandle = new double[1];
        runTurn(fixture.runtime, runtime -> {
            scheduler[0] = new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            double cancelled = scheduler[0].requestAnimationFrame(timestamp -> trace.add("cancelled"));
            scheduler[0].cancelAnimationFrame(cancelled);
            assertEquals(1, fixture.host.getCancelCount(), "last pending callback cancels host frame");

            scheduler[0].requestAnimationFrame(timestamp -> {
                trace.add("first");
                scheduler[0].cancelAnimationFrame(secondHandle[0]);
            });
            secondHandle[0] = scheduler[0].requestAnimationFrame(timestamp -> trace.add("second"));
            scheduler[0].requestAnimationFrame(timestamp -> trace.add("third"));
        });
        fixture.host.fireAt(20_000_000L);
        assertListEquals(Arrays.asList("first", "third"), trace, "same-frame cancellation");
        fixture.runtime.close();
        pass();
    }

    private void testRequestsDuringFrameWaitForNextFrame() throws Throwable {
        Fixture fixture = new Fixture();
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        List<String> trace = new ArrayList<String>();
        runTurn(fixture.runtime, runtime -> {
            scheduler[0] = new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            scheduler[0].requestAnimationFrame(timestamp -> {
                trace.add("first");
                scheduler[0].requestAnimationFrame(next -> trace.add("next"));
            });
            scheduler[0].requestAnimationFrame(timestamp -> trace.add("second"));
        });

        fixture.host.fireAt(10_000_000L);
        assertListEquals(Arrays.asList("first", "second"), trace, "new callback excluded from snapshot");
        assertTrue(fixture.host.hasPendingFrame(), "request during frame arms next frame");
        assertEquals(2, fixture.host.getRequestCount(), "second host frame request");
        fixture.host.fireAt(26_000_000L);
        assertListEquals(Arrays.asList("first", "second", "next"), trace, "next-frame callback");
        fixture.runtime.close();
        pass();
    }

    private void testHandleGenerationAndStaleCancellation() throws Throwable {
        Fixture fixture = new Fixture();
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        List<String> trace = new ArrayList<String>();
        double[] handles = new double[2];
        runTurn(fixture.runtime, runtime -> {
            scheduler[0] = new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            handles[0] = scheduler[0].requestAnimationFrame(timestamp -> trace.add("old"));
            scheduler[0].cancelAnimationFrame(handles[0]);
            handles[1] = scheduler[0].requestAnimationFrame(timestamp -> trace.add("new"));
            assertTrue(handles[0] != handles[1], "reused slot receives new generation");
            scheduler[0].cancelAnimationFrame(handles[0]);
            scheduler[0].cancelAnimationFrame(Double.NaN);
            scheduler[0].cancelAnimationFrame(1.25);
        });
        fixture.host.fireAt(15_000_000L);
        assertListEquals(Arrays.asList("new"), trace, "stale handle cannot cancel reused slot");
        fixture.runtime.close();
        pass();
    }

    private void testCallbackFailureReportsAndContinues() throws Throwable {
        Fixture fixture = new Fixture();
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        List<String> trace = new ArrayList<String>();
        IllegalStateException expected = new IllegalStateException("frame boom");
        runTurn(fixture.runtime, runtime -> {
            scheduler[0] = new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            scheduler[0].requestAnimationFrame(timestamp -> {
                trace.add("before-error");
                throw expected;
            });
            scheduler[0].requestAnimationFrame(timestamp -> trace.add("after-error"));
        });
        fixture.host.fireAt(12_000_000L);
        assertListEquals(Arrays.asList("before-error", "after-error"), trace, "later callback survives");
        assertEquals(1, fixture.callbackErrors.getErrors().size(), "reported callback errors");
        assertSame(expected, fixture.callbackErrors.getErrors().get(0), "callback error identity");
        fixture.runtime.close();
        pass();
    }

    private void testHostRequestFailureRollsBack() throws Throwable {
        Fixture fixture = new Fixture();
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        List<String> trace = new ArrayList<String>();
        IllegalStateException expected = new IllegalStateException("display stopped");
        runTurn(fixture.runtime, runtime -> {
            scheduler[0] = new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            fixture.host.failNextRequest(expected);
            Throwable observed = capture(() -> scheduler[0].requestAnimationFrame(
                    timestamp -> trace.add("failed")));
            assertSame(expected, observed, "host request failure identity");
            scheduler[0].requestAnimationFrame(timestamp -> trace.add("recovered"));
        });
        fixture.host.fireAt(8_000_000L);
        assertListEquals(Arrays.asList("recovered"), trace, "failed registration rolled back");
        fixture.runtime.close();
        pass();
    }

    private void testRuntimeCloseCancelsPendingFrame() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            AnimationFrameScheduler scheduler =
                    new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            scheduler.requestAnimationFrame(timestamp -> {
                throw new AssertionError("closed runtime invoked animation callback");
            });
        });
        assertTrue(fixture.host.hasPendingFrame(), "frame pending before close");
        fixture.runtime.close();
        assertTrue(!fixture.host.hasPendingFrame(), "runtime close removes host frame");
        assertEquals(1, fixture.host.getCancelCount(), "runtime close cancels exact frame");
        pass();
    }

    private void testDeliveredFrameReleasesRuntimeOwnership() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            AnimationFrameScheduler scheduler =
                    new AnimationFrameScheduler(runtime, fixture.host, fixture.callbackErrors);
            scheduler.requestAnimationFrame(timestamp -> {});
        });
        fixture.host.fireAt(4_000_000L);
        fixture.runtime.close();
        assertEquals(0, fixture.host.getCancelCount(), "completed frame is not cancelled on close");
        pass();
    }

    private void testOwnerConfinementAndRuntimeIsolation() throws Throwable {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        runTurn(first.runtime, runtime -> scheduler[0] =
                new AnimationFrameScheduler(runtime, first.host, first.callbackErrors));
        assertThrows(
                IllegalStateException.class,
                () -> scheduler[0].requestAnimationFrame(timestamp -> {}),
                "request outside language turn");
        runTurn(second.runtime, runtime -> assertThrows(
                IllegalStateException.class,
                () -> scheduler[0].getPerformance().now(),
                "other runtime turn cannot access scheduler"));
        first.runtime.close();
        second.runtime.close();
        pass();
    }

    private static Throwable capture(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable error) {
            return error;
        }
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

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) throw new AssertionError(label);
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

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertListEquals(List<String> expected, List<String> actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertThrows(
            Class<? extends Throwable> expected,
            ThrowingRunnable action,
            String label) {
        Throwable error = capture(action);
        if (expected.isInstance(error)) return;
        if (error != null) throw new AssertionError(label + ": wrong exception " + error, error);
        throw new AssertionError(label + ": expected " + expected.getName());
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter runtimeErrors = new CollectingErrorReporter();
        final ManualAnimationFrameHost host = new ManualAnimationFrameHost();
        final CollectingAnimationFrameExceptionReporter callbackErrors =
                new CollectingAnimationFrameExceptionReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, runtimeErrors);
    }
}
