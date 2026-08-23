package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.OwnerExecutor;
import io.github.akisarou.jvmwww.runtime.PlatformPromise;
import io.github.akisarou.jvmwww.runtime.PlatformReferenceDisposer;
import io.github.akisarou.jvmwww.runtime.PromiseRejectionTracker;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorReporter;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free conformance tests for foreign platform Promise completions. */
public final class PlatformPromiseConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new PlatformPromiseConformance().run();
    }

    private void run() throws Throwable {
        testOwnerCompletionStillBecomesLaterHostTask();
        testTypedFulfillmentsCoalesceWithoutBoxing();
        testTypedRejectionsPreserveExactReasons();
        testConcurrentFirstCompletionWins();
        testLosingReferenceCompletionDisposesImmediately();
        testOwnerSettlementBeforeDeliveryDisposesCapturedReference();
        testCloseBeforeCompletionDisposesWithoutPosting();
        testCloseAfterAdmissionDisposesAndStaleWakeIsHarmless();
        testCompletionCloseRaceDisposesExactlyOnce();
        testRejectedOwnerPostRemovesAdmissionAndDisposes();
        testSeparateRuntimeInstancesRemainIsolated();
        System.out.println("Platform Promise conformance: " + passed + " tests passed");
    }

    private void testOwnerCompletionStillBecomesLaterHostTask() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        PlatformPromise[] promise = new PlatformPromise[1];

        runTurn(fixture.runtime, runtime -> {
            promise[0] = runtime.newPlatformPromise();
            promise[0].then((owner, source, destination) -> trace.add("reaction"), null);
            trace.add("before");
            assertTrue(promise[0].tryFulfillVoid(), "owner completion wins token");
            assertTrue(promise[0].isPending(), "platform completion never settles inline");
            trace.add("after");
        });

        assertListEquals(Arrays.asList("before", "after"), trace, "owner completion call ordering");
        assertEquals(1, fixture.executor.getPostCount(), "owner completion schedules one host wake");
        fixture.executor.runNext();
        assertListEquals(
                Arrays.asList("before", "after", "reaction"),
                trace,
                "reaction runs from later owner host task");
        assertTrue(promise[0].isFulfilled(), "owner completion final state");
        fixture.runtime.close();
        pass();
    }

    private void testTypedFulfillmentsCoalesceWithoutBoxing() throws Throwable {
        Fixture fixture = new Fixture();
        PlatformPromise[] promises = new PlatformPromise[4];
        Object reference = new Object();

        runTurn(fixture.runtime, runtime -> {
            for (int index = 0; index < promises.length; index++) {
                promises[index] = runtime.newPlatformPromise();
            }
        });

        AtomicInteger wins = new AtomicInteger();
        Thread worker = new Thread(() -> {
            if (promises[0].tryFulfillVoid()) {
                wins.incrementAndGet();
            }
            if (promises[1].tryFulfillNumber(41.5)) {
                wins.incrementAndGet();
            }
            if (promises[2].tryFulfillBoolean(true)) {
                wins.incrementAndGet();
            }
            if (promises[3].tryFulfillReference(reference)) {
                wins.incrementAndGet();
            }
        }, "platform-typed-fulfillments");
        worker.start();
        worker.join();

        assertEquals(4, wins.get(), "all typed fulfillments win their own token");
        assertEquals(1, fixture.executor.getPostCount(), "typed completion burst coalesces");
        for (PlatformPromise pending : promises) {
            assertTrue(pending.isPending(), "foreign thread cannot settle language Promise");
        }

        fixture.executor.runNext();

        assertEquals(JsPromise.PAYLOAD_VOID, promises[0].getPayloadKind(), "void payload kind");
        assertEquals(JsPromise.PAYLOAD_NUMBER, promises[1].getPayloadKind(), "number payload kind");
        assertDoubleEquals(41.5, promises[1].getNumberPayload(), "number payload");
        assertEquals(JsPromise.PAYLOAD_BOOLEAN, promises[2].getPayloadKind(), "boolean payload kind");
        assertTrue(promises[2].getBooleanPayload(), "boolean payload");
        assertEquals(
                JsPromise.PAYLOAD_REFERENCE,
                promises[3].getPayloadKind(),
                "reference payload kind");
        assertSame(reference, promises[3].getReferencePayload(), "reference payload");
        fixture.runtime.close();
        pass();
    }

    private void testTypedRejectionsPreserveExactReasons() throws Throwable {
        Fixture fixture = new Fixture();
        PlatformPromise[] promises = new PlatformPromise[4];
        Object reference = new Object();

        runTurn(fixture.runtime, runtime -> {
            for (int index = 0; index < promises.length; index++) {
                promises[index] = runtime.newPlatformPromise();
                promises[index].catchRejected((owner, source, destination) -> {});
            }
        });

        AtomicInteger wins = new AtomicInteger();
        Thread worker = new Thread(() -> {
            if (promises[0].tryRejectVoid()) {
                wins.incrementAndGet();
            }
            if (promises[1].tryRejectNumber(7.25)) {
                wins.incrementAndGet();
            }
            if (promises[2].tryRejectBoolean(true)) {
                wins.incrementAndGet();
            }
            if (promises[3].tryRejectReference(reference)) {
                wins.incrementAndGet();
            }
        }, "platform-typed-rejections");
        worker.start();
        worker.join();
        assertEquals(4, wins.get(), "all typed rejections win their own token");
        fixture.executor.runNext();

        for (PlatformPromise rejected : promises) {
            assertTrue(rejected.isRejected(), "typed platform rejection state");
        }
        assertEquals(JsPromise.PAYLOAD_VOID, promises[0].getPayloadKind(), "void reason kind");
        assertDoubleEquals(7.25, promises[1].getNumberPayload(), "number reason");
        assertTrue(promises[2].getBooleanPayload(), "boolean reason");
        assertSame(reference, promises[3].getReferencePayload(), "reference reason");
        fixture.runtime.close();
        pass();
    }

    private void testConcurrentFirstCompletionWins() throws Throwable {
        for (int iteration = 0; iteration < 32; iteration++) {
            Fixture fixture = new Fixture();
            PlatformPromise[] promise = new PlatformPromise[1];
            runTurn(fixture.runtime, runtime -> {
                promise[0] = runtime.newPlatformPromise();
                promise[0].catchRejected((owner, source, destination) -> {});
            });

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            AtomicInteger winner = new AtomicInteger();
            Thread fulfillment = new Thread(() -> {
                await(start);
                if (promise[0].tryFulfillNumber(13.0)) {
                    wins.incrementAndGet();
                    winner.compareAndSet(0, 1);
                }
            }, "platform-race-fulfillment");
            Thread rejection = new Thread(() -> {
                await(start);
                if (promise[0].tryRejectBoolean(true)) {
                    wins.incrementAndGet();
                    winner.compareAndSet(0, 2);
                }
            }, "platform-race-rejection");
            fulfillment.start();
            rejection.start();
            start.countDown();
            fulfillment.join();
            rejection.join();

            assertEquals(1, wins.get(), "exactly one platform completion wins");
            assertTrue(winner.get() == 1 || winner.get() == 2, "winner identity");
            assertEquals(1, fixture.executor.getPostCount(), "race admits one host task");
            fixture.executor.runNext();

            if (winner.get() == 1) {
                assertTrue(promise[0].isFulfilled(), "fulfillment race state");
                assertDoubleEquals(13.0, promise[0].getNumberPayload(), "fulfillment race value");
            } else {
                assertTrue(promise[0].isRejected(), "rejection race state");
                assertTrue(promise[0].getBooleanPayload(), "rejection race value");
            }
            fixture.runtime.close();
        }
        pass();
    }

    private void testLosingReferenceCompletionDisposesImmediately() throws Throwable {
        Fixture fixture = new Fixture();
        PlatformPromise[] promise = new PlatformPromise[1];
        RecordingDisposer disposer = new RecordingDisposer();
        Object losingReference = new Object();

        runTurn(fixture.runtime, runtime -> promise[0] = runtime.newPlatformPromise());
        assertTrue(promise[0].tryFulfillVoid(), "first primitive completion wins");
        assertTrue(
                !promise[0].tryRejectReference(losingReference, disposer),
                "later reference completion loses");
        assertEquals(1, disposer.count.get(), "losing reference disposed once");
        assertSame(losingReference, disposer.last.get(), "losing reference identity");

        fixture.executor.runNext();
        assertTrue(promise[0].isFulfilled(), "winning completion delivered");
        assertEquals(1, disposer.count.get(), "no duplicate losing disposal");
        fixture.runtime.close();
        pass();
    }

    private void testOwnerSettlementBeforeDeliveryDisposesCapturedReference() throws Throwable {
        Fixture fixture = new Fixture();
        PlatformPromise[] promise = new PlatformPromise[1];
        RecordingDisposer disposer = new RecordingDisposer();
        Object reference = new Object();

        runTurn(fixture.runtime, runtime -> promise[0] = runtime.newPlatformPromise());
        assertTrue(
                promise[0].tryFulfillReference(reference, disposer),
                "platform reference captured");

        runTurn(fixture.runtime, runtime -> {
            assertTrue(promise[0].fulfillVoid(), "owner settlement wins before delivery");
        });
        fixture.executor.runNext();

        assertTrue(promise[0].isFulfilled(), "owner settlement remains");
        assertEquals(JsPromise.PAYLOAD_VOID, promise[0].getPayloadKind(), "owner payload remains");
        assertEquals(1, disposer.count.get(), "undelivered captured reference disposed");
        assertSame(reference, disposer.last.get(), "captured reference identity");
        fixture.runtime.close();
        pass();
    }

    private void testCloseBeforeCompletionDisposesWithoutPosting() throws Throwable {
        Fixture fixture = new Fixture();
        PlatformPromise[] promise = new PlatformPromise[1];
        RecordingDisposer disposer = new RecordingDisposer();
        Object reference = new Object();

        runTurn(fixture.runtime, runtime -> promise[0] = runtime.newPlatformPromise());
        fixture.runtime.close();

        assertTrue(
                promise[0].tryFulfillReference(reference, disposer),
                "post-close call wins token");
        assertEquals(0, fixture.executor.getPostCount(), "closed runtime receives no wake");
        assertEquals(1, disposer.count.get(), "post-close reference disposed");
        assertSame(reference, disposer.last.get(), "post-close reference identity");
        pass();
    }

    private void testCloseAfterAdmissionDisposesAndStaleWakeIsHarmless() throws Throwable {
        Fixture fixture = new Fixture();
        PlatformPromise[] promise = new PlatformPromise[1];
        RecordingDisposer disposer = new RecordingDisposer();
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            promise[0] = runtime.newPlatformPromise();
            promise[0].then((owner, source, destination) -> trace.add("reaction"), null);
        });
        assertTrue(
                promise[0].tryFulfillReference(new Object(), disposer),
                "queued reference completion");
        assertEquals(1, fixture.executor.getPendingCallbackCount(), "owner wake pending");

        fixture.runtime.close();
        assertEquals(1, disposer.count.get(), "queued completion discarded on close");
        fixture.executor.runAll();
        assertEquals(0, trace.size(), "stale wake executes no reaction");
        assertEquals(1, disposer.count.get(), "stale wake does not redispose");
        pass();
    }

    private void testCompletionCloseRaceDisposesExactlyOnce() throws Throwable {
        for (int iteration = 0; iteration < 64; iteration++) {
            Fixture fixture = new Fixture();
            PlatformPromise[] promise = new PlatformPromise[1];
            RecordingDisposer disposer = new RecordingDisposer();
            Object reference = new Object();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();

            runTurn(fixture.runtime, runtime -> promise[0] = runtime.newPlatformPromise());
            Thread worker = new Thread(() -> {
                await(start);
                try {
                    promise[0].tryFulfillReference(reference, disposer);
                } catch (Throwable error) {
                    workerFailure.set(error);
                }
            }, "platform-completion-close-race");
            worker.start();
            start.countDown();
            fixture.runtime.close();
            worker.join();
            fixture.executor.runAll();

            assertSame(null, workerFailure.get(), "completion-close race worker failure");
            assertEquals(1, disposer.count.get(), "completion-close race disposal count");
            assertSame(reference, disposer.last.get(), "completion-close race reference");
        }
        pass();
    }

    private void testRejectedOwnerPostRemovesAdmissionAndDisposes() throws Throwable {
        RejectingOwnerExecutor executor = new RejectingOwnerExecutor();
        CollectingErrorReporter errors = new CollectingErrorReporter();
        RuntimeInstance runtime = new RuntimeInstance(executor, errors);
        PlatformPromise[] promise = new PlatformPromise[1];
        RecordingDisposer disposer = new RecordingDisposer();
        int[] markerExecutions = new int[1];

        runTurn(runtime, owner -> promise[0] = owner.newPlatformPromise());
        Throwable failure =
                capture(() -> promise[0].tryFulfillReference(new Object(), disposer));
        assertTrue(failure instanceof IllegalStateException, "owner post failure is explicit");
        assertEquals(1, disposer.count.get(), "failed admission disposes payload");

        executor.rejectPosts = false;
        assertTrue(
                runtime.admitHostTask(owner -> markerExecutions[0]++),
                "later admission succeeds");
        executor.runNext();
        assertEquals(1, markerExecutions[0], "failed admission was removed from queue");
        assertEquals(1, disposer.count.get(), "failed admission not redisposed");
        runtime.close();
        pass();
    }

    private void testSeparateRuntimeInstancesRemainIsolated() throws Throwable {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        PlatformPromise[] firstPromise = new PlatformPromise[1];
        PlatformPromise[] secondPromise = new PlatformPromise[1];

        runTurn(first.runtime, runtime -> firstPromise[0] = runtime.newPlatformPromise());
        runTurn(second.runtime, runtime -> secondPromise[0] = runtime.newPlatformPromise());

        Thread worker = new Thread(() -> {
            firstPromise[0].tryFulfillNumber(1.0);
            secondPromise[0].tryFulfillNumber(2.0);
        }, "platform-separate-runtimes");
        worker.start();
        worker.join();

        assertEquals(1, first.executor.getPostCount(), "first runtime wake");
        assertEquals(1, second.executor.getPostCount(), "second runtime wake");
        first.executor.runNext();
        assertTrue(firstPromise[0].isFulfilled(), "first runtime delivered");
        assertTrue(secondPromise[0].isPending(), "second runtime still pending");
        second.executor.runNext();
        assertDoubleEquals(2.0, secondPromise[0].getNumberPayload(), "second runtime value");
        first.runtime.close();
        second.runtime.close();
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting test latch", error);
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

    private static void assertDoubleEquals(double expected, double actual, String label) {
        if (Double.compare(expected, actual) != 0) {
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
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime =
                new RuntimeInstance(
                        executor,
                        errors,
                        PromiseRejectionTracker.NONE);
    }

    private static final class RecordingDisposer implements PlatformReferenceDisposer {
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<Object> last = new AtomicReference<Object>();

        @Override
        public void dispose(Object value) {
            last.set(value);
            count.incrementAndGet();
        }
    }

    private static final class RejectingOwnerExecutor implements OwnerExecutor {
        final Thread owner = Thread.currentThread();
        final ArrayDeque<Runnable> callbacks = new ArrayDeque<Runnable>();
        boolean rejectPosts = true;

        @Override
        public boolean isOwnerThread() {
            return Thread.currentThread() == owner;
        }

        @Override
        public void post(Runnable callback) {
            if (rejectPosts) {
                throw new IllegalStateException("owner queue rejected callback");
            }
            callbacks.addLast(callback);
        }

        void runNext() {
            Runnable callback = callbacks.pollFirst();
            if (callback == null) {
                throw new AssertionError("No owner callback is pending");
            }
            callback.run();
        }
    }
}
