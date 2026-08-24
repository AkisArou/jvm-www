package io.github.akisarou.jvmwww.web.timing.android.testkit;

import android.os.Looper;
import android.view.Choreographer;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.timing.AnimationFrameHost;
import io.github.akisarou.jvmwww.web.timing.AnimationFrameScheduler;
import io.github.akisarou.jvmwww.web.timing.android.ChoreographerAnimationFrameHost;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic JVM conformance for the Android Choreographer timing adapter. */
public final class ChoreographerAnimationFrameHostConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        Looper.prepare();
        new ChoreographerAnimationFrameHostConformance().run();
    }

    private void run() throws Throwable {
        testFactoryAndMonotonicClock();
        testFusedDeliveryAndRearm();
        testExactCancellationAndStaleDelivery();
        testPostAndRemoveFailuresPreserveState();
        testOwnerConfinement();
        testSchedulerIntegrationAndRuntimeLifecycle();
        System.out.println("Android timing conformance: " + passed + " tests passed");
    }

    private void testFactoryAndMonotonicClock() throws Throwable {
        Throwable[] foreignFailure = new Throwable[1];
        Thread withoutLooper = new Thread(() -> foreignFailure[0] = capture(
                ChoreographerAnimationFrameHost::forCurrentLooper));
        withoutLooper.start();
        withoutLooper.join();
        assertTrue(
                foreignFailure[0] instanceof IllegalStateException,
                "factory rejects a thread without a Looper");

        Choreographer choreographer = resetChoreographer();
        ChoreographerAnimationFrameHost host =
                ChoreographerAnimationFrameHost.forCurrentLooper();
        assertSame(Looper.myLooper(), host.getLooper(), "host records exact owner Looper");
        long first = host.nowNanos();
        long second = host.nowNanos();
        assertTrue(second >= first, "System.nanoTime clock is non-decreasing");
        assertEquals(0, choreographer.getPostCountForTest(), "clock read does not post work");
        pass();
    }

    private void testFusedDeliveryAndRearm() throws Throwable {
        Choreographer choreographer = resetChoreographer();
        ChoreographerAnimationFrameHost host =
                ChoreographerAnimationFrameHost.forCurrentLooper();
        List<String> trace = new ArrayList<String>();
        AnimationFrameHost.FrameCallback[] next = new AnimationFrameHost.FrameCallback[1];
        next[0] = frameTime -> trace.add("next:" + frameTime);
        AnimationFrameHost.FrameCallback first = frameTime -> {
            trace.add("first:" + frameTime);
            host.requestFrame(next[0]);
        };

        host.requestFrame(first);
        assertSame(host, choreographer.getPendingCallbackForTest(), "host is Android callback");
        assertThrows(
                IllegalStateException.class,
                () -> host.requestFrame(next[0]),
                "duplicate pending request rejected");
        choreographer.fireFrameForTest(11L);
        assertTrue(choreographer.hasPendingFrameForTest(), "callback can arm following frame");
        choreographer.fireFrameForTest(22L);
        assertListEquals(
                Arrays.asList("first:11", "next:22"),
                trace,
                "one-shot delivery and in-callback rearm");
        assertEquals(2, choreographer.getPostCountForTest(), "one Android post per frame");
        pass();
    }

    private void testExactCancellationAndStaleDelivery() {
        Choreographer choreographer = resetChoreographer();
        ChoreographerAnimationFrameHost host =
                ChoreographerAnimationFrameHost.forCurrentLooper();
        int[] deliveries = new int[1];
        AnimationFrameHost.FrameCallback target = frameTime -> deliveries[0]++;
        AnimationFrameHost.FrameCallback other = frameTime -> {};

        host.requestFrame(target);
        host.cancelFrame(other);
        assertTrue(choreographer.hasPendingFrameForTest(), "wrong callback cannot cancel request");
        assertEquals(0, choreographer.getRemoveCountForTest(), "wrong callback does not touch Android");
        host.cancelFrame(target);
        assertTrue(!choreographer.hasPendingFrameForTest(), "exact callback removed");
        assertEquals(1, choreographer.getRemoveCountForTest(), "one exact Android removal");
        choreographer.invokeLastPostedForTest(33L);
        assertEquals(0, deliveries[0], "stale platform delivery is ignored");
        pass();
    }

    private void testPostAndRemoveFailuresPreserveState() {
        Choreographer choreographer = resetChoreographer();
        ChoreographerAnimationFrameHost host =
                ChoreographerAnimationFrameHost.forCurrentLooper();
        AnimationFrameHost.FrameCallback callback = frameTime -> {};

        IllegalStateException postFailure = new IllegalStateException("display post failed");
        choreographer.failNextPostForTest(postFailure);
        assertSame(
                postFailure,
                capture(() -> host.requestFrame(callback)),
                "post failure identity");
        assertTrue(!choreographer.hasPendingFrameForTest(), "failed post rolls callback back");

        host.requestFrame(callback);
        IllegalStateException removeFailure = new IllegalStateException("display remove failed");
        choreographer.failNextRemoveForTest(removeFailure);
        assertSame(
                removeFailure,
                capture(() -> host.cancelFrame(callback)),
                "remove failure identity");
        assertTrue(choreographer.hasPendingFrameForTest(), "failed remove retains exact request");
        host.cancelFrame(callback);
        assertTrue(!choreographer.hasPendingFrameForTest(), "retained request can be retried");
        pass();
    }

    private void testOwnerConfinement() throws Throwable {
        Choreographer choreographer = resetChoreographer();
        ChoreographerAnimationFrameHost host =
                ChoreographerAnimationFrameHost.forCurrentLooper();
        AnimationFrameHost.FrameCallback callback = frameTime -> {};
        Throwable[] failures = new Throwable[3];
        Thread foreign = new Thread(() -> {
            failures[0] = capture(() -> host.nowNanos());
            failures[1] = capture(() -> host.requestFrame(callback));
            failures[2] = capture(() -> host.cancelFrame(callback));
        });
        foreign.start();
        foreign.join();

        for (int index = 0; index < failures.length; index++) {
            assertTrue(
                    failures[index] instanceof IllegalStateException,
                    "foreign owner check " + index);
        }
        assertEquals(0, choreographer.getPostCountForTest(), "foreign access posts no frame");
        pass();
    }

    private void testSchedulerIntegrationAndRuntimeLifecycle() throws Throwable {
        Choreographer choreographer = resetChoreographer();
        ChoreographerAnimationFrameHost host =
                ChoreographerAnimationFrameHost.forCurrentLooper();
        Fixture delivered = new Fixture();
        AnimationFrameScheduler[] scheduler = new AnimationFrameScheduler[1];
        List<String> trace = new ArrayList<String>();
        runTurn(delivered.runtime, runtime -> {
            scheduler[0] = new AnimationFrameScheduler(runtime, host);
            scheduler[0].requestAnimationFrame(timestamp -> {
                trace.add("frame");
                runtime.queueMicrotask(owner -> trace.add("microtask"));
            });
        });
        assertSame(host, choreographer.getPendingCallbackForTest(), "scheduler posts fused host");
        choreographer.fireFrameForTest(System.nanoTime());
        assertListEquals(
                Arrays.asList("frame", "microtask"),
                trace,
                "Android delivery enters one scheduler turn and checkpoint");
        delivered.runtime.close();
        assertEquals(0, choreographer.getRemoveCountForTest(), "delivered idle frame not cancelled");

        choreographer.resetForTest();
        ChoreographerAnimationFrameHost pendingHost =
                ChoreographerAnimationFrameHost.forCurrentLooper();
        Fixture pending = new Fixture();
        runTurn(pending.runtime, runtime -> {
            AnimationFrameScheduler pendingScheduler =
                    new AnimationFrameScheduler(runtime, pendingHost);
            pendingScheduler.requestAnimationFrame(timestamp -> {
                throw new AssertionError("closed runtime invoked Android frame callback");
            });
        });
        pending.runtime.close();
        assertTrue(!choreographer.hasPendingFrameForTest(), "runtime close removes Android frame");
        assertEquals(1, choreographer.getRemoveCountForTest(), "runtime close cancels exact host");
        choreographer.invokeLastPostedForTest(System.nanoTime());
        pass();
    }

    private static Choreographer resetChoreographer() {
        Choreographer choreographer = Choreographer.getInstance();
        choreographer.resetForTest();
        return choreographer;
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
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }
}
