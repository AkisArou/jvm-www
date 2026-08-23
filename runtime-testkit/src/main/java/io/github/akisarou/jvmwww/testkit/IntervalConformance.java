package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.PromiseRejectionTracker;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dependency-free conformance tests for repeating logical timers. */
public final class IntervalConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new IntervalConformance().run();
    }

    private void run() throws Throwable {
        testIntervalRearmsFromCallbackCompletion();
        testIntervalCanCancelItself();
        testMicrotaskCanCancelBeforeRearm();
        testClearFunctionsShareOneHandleMap();
        testCallbackFailureDoesNotCancelInterval();
        testIntervalsShareDeadlineOrderingWithTimeouts();
        testStaleIntervalHandleCannotCancelReusedSlot();
        testCloseDiscardsActiveInterval();
        System.out.println("Interval conformance: " + passed + " tests passed");
    }

    private void testIntervalRearmsFromCallbackCompletion() throws Throwable {
        Fixture fixture = new Fixture(64);
        RecordingInterval task = new RecordingInterval(fixture);

        runTurn(fixture.runtime, runtime -> task.handle = runtime.setInterval(task, 10.0));
        fixture.timerHost.advanceMillis(10L);
        assertTrue(fixture.timerHost.runOneDue(), "first interval tick due");

        assertEquals(1, task.executions, "first interval execution");
        assertEquals(
                27_000_000L,
                fixture.timerHost.getArmedDeadlineNanos(),
                "next deadline is callback completion plus delay");

        fixture.timerHost.advanceMillis(10L);
        assertTrue(fixture.timerHost.runOneDue(), "second interval tick due");
        assertEquals(2, task.executions, "second interval execution");
        assertEquals(1, task.discards, "cancelled interval registration discarded once");
        assertTrue(!fixture.timerHost.isArmed(), "self-cancelled second tick is not re-armed");
        pass();
    }

    private void testIntervalCanCancelItself() throws Throwable {
        Fixture fixture = new Fixture(64);
        int[] executions = new int[1];
        int[] discards = new int[1];
        double[] handle = new double[1];

        RuntimeTask callback = new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                executions[0]++;
                runtime.clearInterval(handle[0]);
            }

            @Override
            public void discard() {
                discards[0]++;
            }
        };

        runTurn(fixture.runtime, runtime -> handle[0] = runtime.setInterval(callback, 1.0));
        fixture.timerHost.advanceMillis(1L);
        fixture.timerHost.runOneDue();

        assertEquals(1, executions[0], "self-cancelled interval execution count");
        assertEquals(1, discards[0], "self-cancelled interval discard count");
        assertTrue(!fixture.timerHost.isArmed(), "self-cancelled interval leaves no alarm");
        pass();
    }

    private void testMicrotaskCanCancelBeforeRearm() throws Throwable {
        Fixture fixture = new Fixture(64);
        int[] executions = new int[1];
        int[] discards = new int[1];
        double[] handle = new double[1];

        RuntimeTask callback = new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                executions[0]++;
                runtime.queueMicrotask(owner -> owner.clearInterval(handle[0]));
            }

            @Override
            public void discard() {
                discards[0]++;
            }
        };

        runTurn(fixture.runtime, runtime -> handle[0] = runtime.setInterval(callback, 1.0));
        fixture.timerHost.advanceMillis(1L);
        fixture.timerHost.runOneDue();

        assertEquals(1, executions[0], "interval callback before microtask cancellation");
        assertEquals(1, discards[0], "microtask-cancelled interval discarded once");
        assertTrue(!fixture.timerHost.isArmed(), "microtask cancellation happens before re-arm");
        pass();
    }

    private void testClearFunctionsShareOneHandleMap() throws Throwable {
        Fixture fixture = new Fixture(64);
        RecordingTask interval = new RecordingTask();
        RecordingTask timeout = new RecordingTask();

        runTurn(fixture.runtime, runtime -> {
            double intervalHandle = runtime.setInterval(interval, 1.0);
            runtime.clearTimeout(intervalHandle);
            double timeoutHandle = runtime.setTimeout(timeout, 1.0);
            runtime.clearInterval(timeoutHandle);
        });

        assertEquals(1, interval.discards, "clearTimeout cancels interval");
        assertEquals(1, timeout.discards, "clearInterval cancels timeout");
        assertTrue(!fixture.timerHost.isArmed(), "cross-clear leaves no alarm");
        pass();
    }

    private void testCallbackFailureDoesNotCancelInterval() throws Throwable {
        Fixture fixture = new Fixture(64);
        IllegalStateException expected = new IllegalStateException("interval failure");
        int[] executions = new int[1];
        int[] discards = new int[1];
        double[] handle = new double[1];

        RuntimeTask callback = new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                executions[0]++;
                if (executions[0] == 1) {
                    throw expected;
                }
                runtime.clearInterval(handle[0]);
            }

            @Override
            public void discard() {
                discards[0]++;
            }
        };

        runTurn(fixture.runtime, runtime -> handle[0] = runtime.setInterval(callback, 1.0));
        fixture.timerHost.advanceMillis(1L);
        fixture.timerHost.runOneDue();

        assertEquals(1, executions[0], "failing interval first execution");
        assertEquals(1, fixture.errors.getEntries().size(), "interval failure reported once");
        assertSame(
                RuntimeErrorPhase.HOST_TASK,
                fixture.errors.getEntries().get(0).getPhase(),
                "interval failure phase");
        assertSame(expected, fixture.errors.getEntries().get(0).getError(), "failure identity");
        assertTrue(fixture.timerHost.isArmed(), "non-fatal failure keeps interval active");

        fixture.timerHost.advanceMillis(1L);
        fixture.timerHost.runOneDue();
        assertEquals(2, executions[0], "interval executes after reported failure");
        assertEquals(1, discards[0], "later cancellation discards interval once");
        pass();
    }

    private void testIntervalsShareDeadlineOrderingWithTimeouts() throws Throwable {
        Fixture fixture = new Fixture(64);
        List<String> trace = new ArrayList<String>();
        double[] handle = new double[1];
        int[] ticks = new int[1];

        runTurn(fixture.runtime, runtime -> {
            handle[0] = runtime.setInterval(owner -> {
                ticks[0]++;
                trace.add("interval-" + ticks[0]);
                owner.queueMicrotask(microtaskOwner -> trace.add("interval-microtask"));
                if (ticks[0] == 2) {
                    owner.clearInterval(handle[0]);
                }
            }, 1.8);
            runtime.setTimeout(owner -> trace.add("timeout"), 1.1);
        });

        fixture.timerHost.advanceMillis(1L);
        fixture.timerHost.runOneDue();
        assertListEquals(
                Arrays.asList("interval-1", "interval-microtask", "timeout"),
                trace,
                "interval and timeout share FIFO heap and checkpoints");
        assertEquals(2_000_000L, fixture.timerHost.getArmedDeadlineNanos(), "interval re-arm");

        fixture.timerHost.advanceMillis(1L);
        fixture.timerHost.runOneDue();
        assertListEquals(
                Arrays.asList(
                        "interval-1",
                        "interval-microtask",
                        "timeout",
                        "interval-2",
                        "interval-microtask"),
                trace,
                "second interval tick ordering");
        assertTrue(!fixture.timerHost.isArmed(), "second tick cancelled interval");
        pass();
    }

    private void testStaleIntervalHandleCannotCancelReusedSlot() throws Throwable {
        Fixture fixture = new Fixture(64);
        RecordingTask interval = new RecordingTask();
        int[] timeoutExecutions = new int[1];
        double[] handles = new double[2];

        runTurn(fixture.runtime, runtime -> {
            handles[0] = runtime.setInterval(interval, 1.0);
            runtime.clearInterval(handles[0]);
            handles[1] = runtime.setTimeout(owner -> timeoutExecutions[0]++, 1.0);
            runtime.clearTimeout(handles[0]);
        });

        assertTrue(handles[0] != handles[1], "interval slot reuse advances generation");
        assertEquals(1, interval.discards, "cancelled interval discarded");
        fixture.timerHost.advanceMillis(1L);
        fixture.timerHost.runOneDue();
        assertEquals(1, timeoutExecutions[0], "stale interval handle cannot cancel timeout");
        pass();
    }

    private void testCloseDiscardsActiveInterval() throws Throwable {
        Fixture fixture = new Fixture(64);
        RecordingTask interval = new RecordingTask();

        runTurn(fixture.runtime, runtime -> runtime.setInterval(interval, 10.0));
        fixture.runtime.close();

        assertEquals(1, interval.discards, "runtime close discards interval registration");
        assertTrue(!fixture.timerHost.isArmed(), "runtime close disarms interval alarm");
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

    private static void assertListEquals(List<String> expected, List<String> actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected same instance");
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final ManualTimerHost timerHost = new ManualTimerHost();
        final RuntimeInstance runtime;

        Fixture(int callbacksPerWake) {
            runtime =
                    new RuntimeInstance(
                            executor,
                            errors,
                            PromiseRejectionTracker.NONE,
                            callbacksPerWake,
                            timerHost);
        }
    }

    private static class RecordingTask implements RuntimeTask {
        int executions;
        int discards;

        @Override
        public void execute(RuntimeInstance runtime) {
            executions++;
        }

        @Override
        public void discard() {
            discards++;
        }
    }

    private static final class RecordingInterval extends RecordingTask {
        final Fixture fixture;
        double handle;

        RecordingInterval(Fixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public void execute(RuntimeInstance runtime) {
            executions++;
            fixture.timerHost.advanceMillis(7L);
            if (executions == 2) {
                runtime.clearInterval(handle);
            }
        }
    }
}
