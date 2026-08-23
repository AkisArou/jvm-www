package io.github.akisarou.jvmwww.androidtestkit;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.akisarou.jvmwww.runtime.PromiseRejectionTracker;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorReporter;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.runtime.android.HandlerRuntimeHost;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free conformance tests for the Android Handler runtime host. */
public final class HandlerRuntimeHostConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        Looper.prepare();
        new HandlerRuntimeHostConformance().run();
    }

    private void run() throws Throwable {
        testOwnerPostsAreAsynchronousAndForeignSafe();
        testCurrentLooperFactoryAndOwnerIdentity();
        testAbsoluteAlarmUsesUptimeCeilingAndReplacement();
        testDisarmAndRejectedLooper();
        testTimerOperationsRequireOwnerThread();
        testRuntimeTimersUseOneHandlerAlarmAndCheckpoints();
        testForeignAdmissionsCoalesceOneHandlerWake();
        testRuntimeCloseDisarmsTimerAndMakesStaleWakeHarmless();
        System.out.println("Android host conformance: " + passed + " tests passed");
    }

    private void testOwnerPostsAreAsynchronousAndForeignSafe() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        AtomicBoolean foreignSawOwner = new AtomicBoolean(true);

        fixture.host.post(() -> trace.add("owner"));
        trace.add("caller");
        assertEquals(1, fixture.looper.pendingCountForTest(), "one asynchronous owner post");
        assertListEquals(Arrays.asList("caller"), trace, "owner post is not inline");
        assertTrue(fixture.looper.runOneDueForTest(), "owner post became runnable");

        Thread worker = new Thread(() -> {
            foreignSawOwner.set(fixture.host.isOwnerThread());
            fixture.host.post(() -> trace.add("foreign-post"));
        }, "android-host-foreign-post");
        worker.start();
        worker.join();

        assertTrue(!foreignSawOwner.get(), "foreign thread is not owner");
        assertEquals(1, fixture.looper.pendingCountForTest(), "foreign post uses same Handler queue");
        fixture.looper.runOneDueForTest();
        assertListEquals(
                Arrays.asList("caller", "owner", "foreign-post"),
                trace,
                "foreign post executes later on owner");
        pass();
    }

    private void testCurrentLooperFactoryAndOwnerIdentity() throws Throwable {
        Fixture fixture = new Fixture();
        HandlerRuntimeHost current = HandlerRuntimeHost.forCurrentLooper();
        assertTrue(current.isOwnerThread(), "current Looper factory owner identity");
        assertSame(fixture.looper, current.getLooper(), "current Looper factory target");

        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                HandlerRuntimeHost.forCurrentLooper();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "android-host-no-looper");
        worker.start();
        worker.join();
        assertTrue(failure.get() instanceof IllegalStateException, "missing Looper refusal");
        pass();
    }

    private void testAbsoluteAlarmUsesUptimeCeilingAndReplacement() {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        SystemClock.setUptimeMillisForTest(10L);

        assertEquals(10_000_000L, fixture.host.nowNanos(), "uptime clock conversion");
        fixture.host.arm(10_000_001L, () -> trace.add("first"));
        assertEquals(11L, fixture.looper.earliestUptimeMillisForTest(), "deadline rounds upward");

        fixture.host.arm(12_000_000L, () -> trace.add("second"));
        assertEquals(1, fixture.looper.pendingCountForTest(), "replacement leaves one alarm");
        assertEquals(12L, fixture.looper.earliestUptimeMillisForTest(), "replacement deadline");

        SystemClock.setUptimeMillisForTest(11L);
        assertTrue(!fixture.looper.runOneDueForTest(), "absolute alarm does not fire early");
        SystemClock.advanceMillisForTest(1L);
        assertTrue(fixture.looper.runOneDueForTest(), "replacement alarm fires when due");
        assertListEquals(Arrays.asList("second"), trace, "replaced callback cannot fire");
        pass();
    }

    private void testDisarmAndRejectedLooper() {
        Fixture fixture = new Fixture();
        fixture.host.arm(1_000_000L, () -> {});
        fixture.host.disarm();
        assertEquals(0, fixture.looper.pendingCountForTest(), "disarm removes alarm");

        fixture.looper.setAcceptingForTest(false);
        Throwable postFailure = capture(() -> fixture.host.post(() -> {}));
        Throwable alarmFailure = capture(() -> fixture.host.arm(1_000_000L, () -> {}));
        assertTrue(postFailure instanceof IllegalStateException, "rejected owner post is explicit");
        assertTrue(alarmFailure instanceof IllegalStateException, "rejected timer alarm is explicit");
        fixture.looper.setAcceptingForTest(true);
        pass();
    }

    private void testTimerOperationsRequireOwnerThread() throws Throwable {
        Fixture fixture = new Fixture();
        AtomicReference<Throwable> nowFailure = new AtomicReference<Throwable>();
        AtomicReference<Throwable> armFailure = new AtomicReference<Throwable>();
        AtomicReference<Throwable> disarmFailure = new AtomicReference<Throwable>();

        Thread worker = new Thread(() -> {
            nowFailure.set(capture(() -> fixture.host.nowNanos()));
            armFailure.set(capture(() -> fixture.host.arm(1_000_000L, () -> {})));
            disarmFailure.set(capture(() -> fixture.host.disarm()));
        }, "android-host-foreign-timer");
        worker.start();
        worker.join();

        assertTrue(nowFailure.get() instanceof IllegalStateException, "foreign clock refusal");
        assertTrue(armFailure.get() instanceof IllegalStateException, "foreign arm refusal");
        assertTrue(disarmFailure.get() instanceof IllegalStateException, "foreign disarm refusal");
        pass();
    }

    private void testRuntimeTimersUseOneHandlerAlarmAndCheckpoints() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            runtime.setTimeout(owner -> {
                trace.add("timer-1");
                owner.queueMicrotask(microtaskOwner -> trace.add("microtask-1"));
            }, 1.0);
            runtime.setTimeout(owner -> trace.add("timer-2"), 1.0);
        });

        assertEquals(1, fixture.looper.pendingCountForTest(), "logical timers share one Handler alarm");
        SystemClock.advanceMillisForTest(1L);
        fixture.looper.runOneDueForTest();
        assertListEquals(
                Arrays.asList("timer-1", "microtask-1", "timer-2"),
                trace,
                "Handler alarm preserves runtime checkpoints");
        fixture.runtime.close();
        assertEquals(0, fixture.errors.entries.size(), "timer integration errors");
        pass();
    }

    private void testForeignAdmissionsCoalesceOneHandlerWake() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        AtomicBoolean admitted = new AtomicBoolean();

        Thread worker = new Thread(() -> {
            boolean first = fixture.runtime.admitHostTask(owner -> {
                trace.add("a");
                owner.queueMicrotask(microtaskOwner -> trace.add("microtask-a"));
            });
            boolean second = fixture.runtime.admitHostTask(owner -> trace.add("b"));
            boolean third = fixture.runtime.admitHostTask(owner -> trace.add("c"));
            admitted.set(first && second && third);
        }, "android-host-admission-burst");
        worker.start();
        worker.join();

        assertTrue(admitted.get(), "foreign burst admitted");
        assertEquals(1, fixture.looper.pendingCountForTest(), "foreign burst posts one Handler wake");
        fixture.looper.runOneDueForTest();
        assertListEquals(
                Arrays.asList("a", "microtask-a", "b", "c"),
                trace,
                "foreign host tasks execute with checkpoints");
        fixture.runtime.close();
        pass();
    }

    private void testRuntimeCloseDisarmsTimerAndMakesStaleWakeHarmless() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTask timer = new RecordingTask();
        RecordingTask admitted = new RecordingTask();

        runTurn(fixture.runtime, runtime -> runtime.setTimeout(timer, 10.0));
        assertTrue(fixture.runtime.admitHostTask(admitted), "host task admitted before close");
        assertEquals(2, fixture.looper.pendingCountForTest(), "timer alarm and owner wake queued");

        fixture.runtime.close();
        assertEquals(1, fixture.looper.pendingCountForTest(), "close removes timer alarm only");
        assertEquals(1, timer.discards, "close discards timer callback");
        assertEquals(1, admitted.discards, "close discards admitted callback");

        fixture.looper.runOneDueForTest();
        assertEquals(0, timer.executions + admitted.executions, "stale owner wake is harmless");
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

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class Fixture {
        final Looper looper = Looper.myLooper();
        final HandlerRuntimeHost host;
        final CollectingErrors errors = new CollectingErrors();
        final RuntimeInstance runtime;

        Fixture() {
            looper.resetForTest();
            SystemClock.setUptimeMillisForTest(0L);
            host = new HandlerRuntimeHost(new Handler(looper));
            runtime =
                    new RuntimeInstance(
                            host,
                            errors,
                            PromiseRejectionTracker.NONE,
                            64,
                            host);
        }
    }

    private static final class CollectingErrors implements RuntimeErrorReporter {
        final List<Throwable> entries = new ArrayList<Throwable>();

        @Override
        public void report(RuntimeInstance runtime, RuntimeErrorPhase phase, Throwable error) {
            entries.add(error);
        }
    }

    private static final class RecordingTask implements RuntimeTask {
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
}
