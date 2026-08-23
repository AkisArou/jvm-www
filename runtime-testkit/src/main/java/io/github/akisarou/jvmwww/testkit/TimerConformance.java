package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.OwnerExecutor;
import io.github.akisarou.jvmwww.runtime.PromiseRejectionTracker;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorReporter;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free conformance tests for the logical one-shot timer core. */
public final class TimerConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new TimerConformance().run();
    }

    private void run() throws Throwable {
        testDelayCoercionAndRegistrationOrder();
        testMicrotasksRunBetweenDueTimers();
        testCancellationAndStaleGeneration();
        testEarliestDeadlineRearming();
        testFairnessBudget();
        testTimerCanRegisterAnotherTimer();
        testCloseDiscardsAndStaleWakeIsHarmless();
        testCallbackFailureDoesNotStrandTimers();
        testOwnerAndTurnRequirements();
        testUnsupportedHostRefusesSetTimeout();
        System.out.println("Timer conformance: " + passed + " tests passed");
    }

    private void testDelayCoercionAndRegistrationOrder() throws Throwable {
        Fixture fixture = new Fixture(64);
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            runtime.setTimeout(append(trace, "nan"), Double.NaN);
            runtime.setTimeout(append(trace, "negative"), -5.0);
            runtime.setTimeout(append(trace, "sub-ms"), 0.9);
            runtime.setTimeout(append(trace, "fractional"), 1.8);
            runtime.setTimeout(append(trace, "too-large"), 2_147_483_648.0);
        });

        assertEquals(1, fixture.timers.getArmCount(), "equal-deadline alarm coalescing");
        assertEquals(1_000_000L, fixture.timers.getArmedDeadlineNanos(), "coerced deadline");
        assertEquals(0, fixture.executor.getPostCount(), "timer registration owner posts");

        fixture.timers.advanceMillis(1);
        assertTrue(fixture.timers.runOneDue(), "coerced timers became due");
        assertListEquals(
                Arrays.asList("nan", "negative", "sub-ms", "fractional", "too-large"),
                trace,
                "coerced timer registration order");
        assertTrue(!fixture.timers.isArmed(), "one-shot timers leave no alarm");
        pass();
    }

    private void testMicrotasksRunBetweenDueTimers() throws Throwable {
        Fixture fixture = new Fixture(64);
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            runtime.setTimeout(owner -> {
                trace.add("timer-1");
                owner.queueMicrotask(append(trace, "microtask-1"));
            }, 1.0);
            runtime.setTimeout(append(trace, "timer-2"), 1.0);
        });

        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();
        assertListEquals(
                Arrays.asList("timer-1", "microtask-1", "timer-2"),
                trace,
                "microtask checkpoint between timer host tasks");
        pass();
    }

    private void testCancellationAndStaleGeneration() throws Throwable {
        Fixture fixture = new Fixture(64);
        RecordingTask cancelled = new RecordingTask("cancelled");
        List<String> trace = new ArrayList<String>();
        double[] handles = new double[2];

        runTurn(fixture.runtime, runtime -> {
            handles[0] = runtime.setTimeout(cancelled, 5.0);
            runtime.clearTimeout(handles[0]);
            handles[1] = runtime.setTimeout(append(trace, "replacement"), 5.0);
            runtime.clearTimeout(handles[0]);
            runtime.clearTimeout(Double.NaN);
            runtime.clearTimeout(handles[1] + 0.5);
        });

        assertTrue(handles[0] != handles[1], "reused timer slot advances its generation");
        assertEquals(1, cancelled.discards, "cancelled timer discarded exactly once");
        fixture.timers.advanceMillis(5);
        fixture.timers.runOneDue();
        assertListEquals(Arrays.asList("replacement"), trace, "stale handle cannot cancel reuse");
        pass();
    }

    private void testEarliestDeadlineRearming() throws Throwable {
        Fixture fixture = new Fixture(64);
        double[] handles = new double[3];

        runTurn(fixture.runtime, runtime -> {
            handles[0] = runtime.setTimeout(owner -> {}, 20.0);
            handles[1] = runtime.setTimeout(owner -> {}, 10.0);
            handles[2] = runtime.setTimeout(owner -> {}, 30.0);
        });

        assertEquals(2, fixture.timers.getArmCount(), "only earlier insertion re-arms");
        assertEquals(10_000_000L, fixture.timers.getArmedDeadlineNanos(), "earliest deadline");

        runTurn(fixture.runtime, runtime -> runtime.clearTimeout(handles[1]));
        assertEquals(3, fixture.timers.getArmCount(), "cancelling earliest re-arms next");
        assertEquals(20_000_000L, fixture.timers.getArmedDeadlineNanos(), "next deadline");

        runTurn(fixture.runtime, runtime -> runtime.clearTimeout(handles[2]));
        assertEquals(3, fixture.timers.getArmCount(), "non-earliest cancellation does not re-arm");

        runTurn(fixture.runtime, runtime -> runtime.clearTimeout(handles[0]));
        assertTrue(!fixture.timers.isArmed(), "last cancellation disarms host");
        assertEquals(1, fixture.timers.getDisarmCount(), "single final disarm");
        pass();
    }

    private void testFairnessBudget() throws Throwable {
        Fixture fixture = new Fixture(2);
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            runtime.setTimeout(append(trace, "a"), 1.0);
            runtime.setTimeout(append(trace, "b"), 1.0);
            runtime.setTimeout(append(trace, "c"), 1.0);
        });
        fixture.timers.advanceMillis(1);

        fixture.timers.runOneDue();
        assertListEquals(Arrays.asList("a", "b"), trace, "first bounded timer wake");
        assertTrue(fixture.timers.isArmed(), "remaining due timer re-arms host");
        assertEquals(1_000_000L, fixture.timers.getArmedDeadlineNanos(), "due re-arm deadline");

        fixture.timers.runOneDue();
        assertListEquals(Arrays.asList("a", "b", "c"), trace, "second bounded timer wake");
        assertTrue(!fixture.timers.isArmed(), "fairness continuation completed");
        pass();
    }

    private void testTimerCanRegisterAnotherTimer() throws Throwable {
        Fixture fixture = new Fixture(64);
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> runtime.setTimeout(owner -> {
            trace.add("first");
            owner.setTimeout(append(trace, "second"), 1.0);
        }, 1.0));

        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();
        assertListEquals(Arrays.asList("first"), trace, "nested timer not same-deadline inline");
        assertEquals(2_000_000L, fixture.timers.getArmedDeadlineNanos(), "nested deadline");

        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();
        assertListEquals(Arrays.asList("first", "second"), trace, "nested timer delivery");
        pass();
    }

    private void testCloseDiscardsAndStaleWakeIsHarmless() throws Throwable {
        Fixture fixture = new Fixture(64);
        RecordingTask first = new RecordingTask("first");
        RecordingTask second = new RecordingTask("second");

        runTurn(fixture.runtime, runtime -> {
            runtime.setTimeout(first, 10.0);
            runtime.setTimeout(second, 20.0);
        });
        Runnable staleWake = fixture.timers.getArmedCallback();
        fixture.runtime.close();

        assertEquals(1, first.discards, "first close discard");
        assertEquals(1, second.discards, "second close discard");
        assertTrue(!fixture.timers.isArmed(), "close disarms timer host");
        staleWake.run();
        assertEquals(0, first.executions + second.executions, "stale post-close wake is no-op");
        pass();
    }

    private void testCallbackFailureDoesNotStrandTimers() throws Throwable {
        Fixture fixture = new Fixture(64);
        List<String> trace = new ArrayList<String>();
        IllegalStateException expected = new IllegalStateException("timer-failure");

        runTurn(fixture.runtime, runtime -> {
            runtime.setTimeout(owner -> {
                throw expected;
            }, 1.0);
            runtime.setTimeout(append(trace, "after-failure"), 1.0);
        });
        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();

        assertListEquals(Arrays.asList("after-failure"), trace, "later due timer after failure");
        assertEquals(1, fixture.errors.size(), "timer callback failure count");
        assertSame(RuntimeErrorPhase.HOST_TASK, fixture.errors.get(0).phase, "timer failure phase");
        assertSame(expected, fixture.errors.get(0).error, "timer failure identity");
        pass();
    }

    private void testOwnerAndTurnRequirements() throws Throwable {
        Fixture fixture = new Fixture(64);
        Throwable idleFailure = capture(() -> fixture.runtime.setTimeout(owner -> {}, 1.0));
        assertTrue(idleFailure instanceof IllegalStateException, "idle setTimeout refusal");

        double[] handle = new double[1];
        runTurn(fixture.runtime, runtime -> handle[0] = runtime.setTimeout(owner -> {}, 1.0));
        AtomicReference<Throwable> foreignFailure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                fixture.runtime.clearTimeout(handle[0]);
            } catch (Throwable error) {
                foreignFailure.set(error);
            }
        }, "foreign-clear-timeout");
        worker.start();
        worker.join();
        assertTrue(foreignFailure.get() instanceof IllegalStateException, "foreign clear refusal");
        pass();
    }

    private void testUnsupportedHostRefusesSetTimeout() throws Throwable {
        ManualOwnerExecutor executor = new ManualOwnerExecutor();
        CollectingErrors errors = new CollectingErrors();
        RuntimeInstance runtime = new RuntimeInstance(executor, errors);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        runTurn(runtime, owner -> {
            try {
                owner.setTimeout(ignored -> {}, 1.0);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        assertTrue(failure.get() instanceof UnsupportedOperationException, "unsupported timer host");
        pass();
    }

    private static RuntimeTask append(List<String> trace, String value) {
        return runtime -> trace.add(value);
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try {
            task.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    private static Throwable capture(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    private void pass() {
        passed++;
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
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

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected same instance");
        }
    }

    private static void assertListEquals(List<String> expected, List<String> actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrors errors = new CollectingErrors();
        final ManualTimerHost timers = new ManualTimerHost();
        final RuntimeInstance runtime;

        Fixture(int callbacksPerWake) {
            runtime = new RuntimeInstance(
                    executor,
                    errors,
                    PromiseRejectionTracker.NONE,
                    callbacksPerWake,
                    timers);
        }
    }

    private static final class ManualOwnerExecutor implements OwnerExecutor {
        final Thread owner = Thread.currentThread();
        final ArrayDeque<Runnable> posts = new ArrayDeque<Runnable>();
        int postCount;

        @Override
        public boolean isOwnerThread() {
            return Thread.currentThread() == owner;
        }

        @Override
        public void post(Runnable callback) {
            posts.addLast(callback);
            postCount++;
        }

        int getPostCount() {
            return postCount;
        }
    }

    private static final class ErrorEntry {
        final RuntimeErrorPhase phase;
        final Throwable error;

        ErrorEntry(RuntimeErrorPhase phase, Throwable error) {
            this.phase = phase;
            this.error = error;
        }
    }

    private static final class CollectingErrors implements RuntimeErrorReporter {
        final List<ErrorEntry> entries = new ArrayList<ErrorEntry>();

        @Override
        public void report(RuntimeInstance runtime, RuntimeErrorPhase phase, Throwable error) {
            entries.add(new ErrorEntry(phase, error));
        }

        int size() {
            return entries.size();
        }

        ErrorEntry get(int index) {
            return entries.get(index);
        }
    }

    private static final class RecordingTask implements RuntimeTask {
        final String name;
        int executions;
        int discards;

        RecordingTask(String name) {
            this.name = name;
        }

        @Override
        public void execute(RuntimeInstance runtime) {
            executions++;
        }

        @Override
        public void discard() {
            discards++;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
