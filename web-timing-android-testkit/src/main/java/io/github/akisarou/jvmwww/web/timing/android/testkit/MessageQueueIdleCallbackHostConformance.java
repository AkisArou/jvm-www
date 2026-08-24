package io.github.akisarou.jvmwww.web.timing.android.testkit;

import android.os.Looper;
import android.os.MessageQueue;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.testkit.ManualTimerHost;
import io.github.akisarou.jvmwww.web.timing.IdleCallbackHost;
import io.github.akisarou.jvmwww.web.timing.IdleCallbackScheduler;
import io.github.akisarou.jvmwww.web.timing.android.MessageQueueIdleCallbackHost;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic JVM conformance for the Android MessageQueue idle-period adapter. */
public final class MessageQueueIdleCallbackHostConformance {
    private static final long TEST_IDLE_BUDGET_NANOS = 50_000_000L;

    private int passed;

    public static void main(String[] args) throws Throwable {
        Looper.prepare();
        new MessageQueueIdleCallbackHostConformance().run();
    }

    private void run() throws Throwable {
        testFactoryBudgetAndClock();
        testFusedDeliveryAndRearm();
        testExactCancellationAndStaleDelivery();
        testAddAndRemoveFailuresPreserveState();
        testOwnerConfinement();
        testSchedulerIntegrationAndCheckpoint();
        testRuntimeCloseCancelsIdleAndTimeout();
        System.out.println("Android idle timing conformance: " + passed + " tests passed");
    }

    private void testFactoryBudgetAndClock() throws Throwable {
        Throwable[] noLooperFailure = new Throwable[1];
        Thread withoutLooper = new Thread(() -> noLooperFailure[0] = capture(
                () -> MessageQueueIdleCallbackHost.forCurrentLooper()));
        withoutLooper.start();
        withoutLooper.join();
        assertTrue(
                noLooperFailure[0] instanceof IllegalStateException,
                "factory rejects a thread without a Looper");

        MessageQueue queue = resetQueue();
        MessageQueueIdleCallbackHost host = MessageQueueIdleCallbackHost.forCurrentLooper();
        assertSame(Looper.myLooper(), host.getLooper(), "host records exact owner Looper");
        assertEquals(
                MessageQueueIdleCallbackHost.DEFAULT_IDLE_BUDGET_NANOS,
                host.getIdleBudgetNanos(),
                "default conservative idle budget");
        long first = host.nowNanos();
        long second = host.nowNanos();
        assertTrue(second >= first, "System.nanoTime clock is non-decreasing");
        assertEquals(0, queue.getAddCountForTest(), "clock and factory post no work");
        assertThrows(
                IllegalArgumentException.class,
                () -> MessageQueueIdleCallbackHost.forCurrentLooper(0L),
                "zero budget rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> MessageQueueIdleCallbackHost.forCurrentLooper(-1L),
                "negative budget rejected");
        pass();
    }

    private void testFusedDeliveryAndRearm() {
        MessageQueue queue = resetQueue();
        MessageQueueIdleCallbackHost host = MessageQueueIdleCallbackHost.forCurrentLooper(
                TEST_IDLE_BUDGET_NANOS);
        List<String> trace = new ArrayList<String>();
        long[] remainingNanos = new long[1];
        IdleCallbackHost.IdleCallback[] next = new IdleCallbackHost.IdleCallback[1];
        next[0] = deadline -> trace.add("next");
        IdleCallbackHost.IdleCallback first = deadline -> {
            remainingNanos[0] = deadline - host.nowNanos();
            trace.add("first");
            host.requestIdle(next[0]);
        };

        host.requestIdle(first);
        assertSame(host, queue.getFirstIdleHandlerForTest(), "host is exact IdleHandler");
        assertEquals(1, queue.getAddCountForTest(), "one queue registration");
        assertThrows(
                IllegalStateException.class,
                () -> host.requestIdle(next[0]),
                "duplicate pending request rejected");

        assertTrue(queue.fireOneIdleForTest(), "in-delivery rearm keeps handler installed");
        assertEquals(1, queue.getAddCountForTest(), "rearm does not add duplicate handler");
        assertEquals(1, queue.getIdleHandlerCountForTest(), "one persistent handler");
        assertTrue(
                remainingNanos[0] > 0L && remainingNanos[0] <= TEST_IDLE_BUDGET_NANOS,
                "deadline uses bounded monotonic budget");

        assertTrue(!queue.fireOneIdleForTest(), "one-shot completion removes idle handler");
        assertEquals(0, queue.getIdleHandlerCountForTest(), "handler removed after final delivery");
        assertListEquals(Arrays.asList("first", "next"), trace, "rearmed delivery order");
        pass();
    }

    private void testExactCancellationAndStaleDelivery() {
        MessageQueue queue = resetQueue();
        MessageQueueIdleCallbackHost host = MessageQueueIdleCallbackHost.forCurrentLooper();
        int[] deliveries = new int[1];
        IdleCallbackHost.IdleCallback target = deadline -> deliveries[0]++;
        IdleCallbackHost.IdleCallback other = deadline -> {};

        host.requestIdle(target);
        host.cancelIdle(other);
        assertEquals(1, queue.getIdleHandlerCountForTest(), "wrong callback cannot cancel");
        assertEquals(0, queue.getRemoveCountForTest(), "wrong callback does not touch queue");
        host.cancelIdle(target);
        assertEquals(0, queue.getIdleHandlerCountForTest(), "exact callback removed");
        assertEquals(1, queue.getRemoveCountForTest(), "one exact queue removal");
        assertTrue(!queue.invokeLastAddedForTest(), "stale queue delivery asks for removal");
        assertEquals(0, deliveries[0], "stale delivery invokes no scheduler callback");
        pass();
    }

    private void testAddAndRemoveFailuresPreserveState() {
        MessageQueue queue = resetQueue();
        MessageQueueIdleCallbackHost host = MessageQueueIdleCallbackHost.forCurrentLooper();
        List<String> trace = new ArrayList<String>();
        IdleCallbackHost.IdleCallback callback = deadline -> trace.add("delivered");

        IllegalStateException addFailure = new IllegalStateException("queue add failed");
        queue.failNextAddForTest(addFailure);
        assertSame(addFailure, capture(() -> host.requestIdle(callback)), "add failure identity");
        assertEquals(0, queue.getIdleHandlerCountForTest(), "failed add rolls state back");

        host.requestIdle(callback);
        IllegalStateException removeFailure = new IllegalStateException("queue remove failed");
        queue.failNextRemoveForTest(removeFailure);
        assertSame(
                removeFailure,
                capture(() -> host.cancelIdle(callback)),
                "remove failure identity");
        assertEquals(1, queue.getIdleHandlerCountForTest(), "failed remove retains exact request");
        host.cancelIdle(callback);
        assertEquals(0, queue.getIdleHandlerCountForTest(), "retained request can be retried");

        host.requestIdle(callback);
        queue.fireOneIdleForTest();
        assertListEquals(Arrays.asList("delivered"), trace, "host recovers after failures");
        pass();
    }

    private void testOwnerConfinement() throws Throwable {
        MessageQueue queue = resetQueue();
        MessageQueueIdleCallbackHost host = MessageQueueIdleCallbackHost.forCurrentLooper();
        IdleCallbackHost.IdleCallback callback = deadline -> {};
        Throwable[] failures = new Throwable[3];
        Thread foreign = new Thread(() -> {
            failures[0] = capture(() -> host.nowNanos());
            failures[1] = capture(() -> host.requestIdle(callback));
            failures[2] = capture(() -> host.cancelIdle(callback));
        });
        foreign.start();
        foreign.join();

        for (int index = 0; index < failures.length; index++) {
            assertTrue(
                    failures[index] instanceof IllegalStateException,
                    "foreign owner check " + index);
        }
        assertEquals(0, queue.getAddCountForTest(), "foreign access registers no handler");
        pass();
    }

    private void testSchedulerIntegrationAndCheckpoint() throws Throwable {
        MessageQueue queue = resetQueue();
        MessageQueueIdleCallbackHost host = MessageQueueIdleCallbackHost.forCurrentLooper(
                TEST_IDLE_BUDGET_NANOS);
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        runTurn(fixture.runtime, runtime -> {
            IdleCallbackScheduler scheduler = new IdleCallbackScheduler(runtime, host);
            scheduler.requestIdleCallback(deadline -> {
                trace.add("first");
                runtime.queueMicrotask(owner -> trace.add("microtask"));
            });
            scheduler.requestIdleCallback(deadline -> trace.add("second"));
        });

        assertEquals(1, queue.getAddCountForTest(), "scheduler burst adds one IdleHandler");
        assertTrue(queue.fireOneIdleForTest(), "remaining scheduler work keeps handler");
        assertListEquals(
                Arrays.asList("first", "microtask"),
                trace,
                "first idle callback receives one final checkpoint");
        assertEquals(1, queue.getAddCountForTest(), "scheduler rearm reuses current handler");
        assertTrue(!queue.fireOneIdleForTest(), "second delivery drains scheduler work");
        assertListEquals(
                Arrays.asList("first", "microtask", "second"),
                trace,
                "one callback per queue-idle notification");
        fixture.runtime.close();
        assertEquals(0, queue.getRemoveCountForTest(), "delivered idle scheduler is not recancelled");
        pass();
    }

    private void testRuntimeCloseCancelsIdleAndTimeout() throws Throwable {
        MessageQueue queue = resetQueue();
        MessageQueueIdleCallbackHost host = MessageQueueIdleCallbackHost.forCurrentLooper();
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            IdleCallbackScheduler scheduler = new IdleCallbackScheduler(runtime, host);
            scheduler.requestIdleCallback(deadline -> {
                throw new AssertionError("closed runtime invoked Android idle callback");
            }, 5.0);
        });
        MessageQueue.IdleHandler stale = queue.getFirstIdleHandlerForTest();
        assertTrue(stale != null, "idle handler pending before close");
        assertTrue(fixture.timers.isArmed(), "one timeout alarm pending before close");

        fixture.runtime.close();
        assertEquals(0, queue.getIdleHandlerCountForTest(), "runtime close removes IdleHandler");
        assertEquals(1, queue.getRemoveCountForTest(), "runtime close cancels exact idle request");
        assertTrue(!fixture.timers.isArmed(), "runtime close clears shared timeout alarm");
        assertTrue(!stale.queueIdle(), "stale post-close idle delivery is harmless");
        pass();
    }

    private static MessageQueue resetQueue() {
        MessageQueue queue = Looper.myQueue();
        queue.resetForTest();
        return queue;
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
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected same instance");
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
        if (expected.isInstance(error)) {
            return;
        }
        if (error != null) {
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
        final ManualTimerHost timers = new ManualTimerHost();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors, timers);
    }
}
