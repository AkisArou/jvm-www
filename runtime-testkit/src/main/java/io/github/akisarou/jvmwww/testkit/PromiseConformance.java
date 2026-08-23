package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsThrownValue;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.PromiseRejectionTracker;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free conformance tests for the direct-JVM Promise core. */
public final class PromiseConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        PromiseConformance suite = new PromiseConformance();
        suite.run();
    }

    private void run() throws Throwable {
        testPromiseAndQueueMicrotaskOrdering();
        testThenOnSettledPromiseIsAsynchronousAndSpecialized();
        testReactionsRunFifoToExhaustion();
        testMissingHandlersPropagateSettlement();
        testThrownReactionRejectsWithExactValue();
        testJavaReactionFailureRejectsByReference();
        testFirstSettlementWinsAndAdoptionLocks();
        testSettledPromiseAdoptionUsesMicrotask();
        testSelfResolutionRejectsTypeError();
        testUnhandledCheckpointAndLateHandle();
        testHandledBeforeCheckpointSuppressesUnhandled();
        testPropagationMovesUnhandledToChild();
        testPromiseRequiresOwnerAndActiveTurn();
        testCrossRuntimeAdoptionIsRefused();
        testUnresolvedReactionReturnFulfillsVoid();
        testRejectionTrackerFailureIsReported();

        System.out.println("Promise conformance: " + passed + " tests passed");
    }

    private void testPromiseAndQueueMicrotaskOrdering() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            trace.add("sync");
            runtime.queueMicrotask(append(trace, "microtask"));

            JsPromise settled = runtime.newPromise();
            settled.fulfillVoid();
            settled.then((owner, source, destination) -> {
                trace.add("promise");
                owner.queueMicrotask(append(trace, "nested"));
            }, null);

            trace.add("end");
        });

        assertListEquals(
                Arrays.asList("sync", "end", "microtask", "promise", "nested"),
                trace,
                "Promise and queueMicrotask reference ordering");
        pass();
    }

    private void testThenOnSettledPromiseIsAsynchronousAndSpecialized() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        JsPromise[] child = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            assertTrue(source.fulfillNumber(41.0), "first Promise fulfillment");
            child[0] = source.then((owner, settled, destination) -> {
                trace.add("reaction");
                destination.fulfillNumber(settled.getNumberPayload() + 1.0);
            }, null);
            trace.add("sync");
            assertTrue(child[0].isPending(), "settled then remains asynchronous");
        });

        assertListEquals(Arrays.asList("sync", "reaction"), trace, "settled then ordering");
        assertTrue(child[0].isFulfilled(), "settled then destination state");
        assertEquals(JsPromise.PAYLOAD_NUMBER, child[0].getPayloadKind(), "number payload kind");
        assertDoubleEquals(42.0, child[0].getNumberPayload(), "number payload value");
        pass();
    }

    private void testReactionsRunFifoToExhaustion() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            source.then((owner, settled, destination) -> {
                trace.add("a");
                source.then((nestedOwner, nestedSource, nestedDestination) -> trace.add("c"), null);
            }, null);
            source.then((owner, settled, destination) -> trace.add("b"), null);
            source.fulfillVoid();
        });

        assertListEquals(Arrays.asList("a", "b", "c"), trace, "Promise reaction FIFO");
        pass();
    }

    private void testMissingHandlersPropagateSettlement() throws Throwable {
        Fixture fixture = new Fixture();
        Object marker = new Object();
        JsPromise[] fulfilledChild = new JsPromise[1];
        JsPromise[] rejectedChild = new JsPromise[1];
        JsPromise[] recovered = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise fulfilled = runtime.newPromise();
            fulfilledChild[0] = fulfilled.then(null, null);
            fulfilled.fulfillReference(marker);

            JsPromise rejected = runtime.newPromise();
            rejectedChild[0] = rejected.then(null, null);
            recovered[0] = rejectedChild[0].catchRejected((owner, source, destination) -> {
                assertDoubleEquals(8.0, source.getNumberPayload(), "propagated rejection value");
                destination.fulfillBoolean(true);
            });
            rejected.rejectNumber(8.0);
        });

        assertTrue(fulfilledChild[0].isFulfilled(), "fulfillment pass-through state");
        assertSame(marker, fulfilledChild[0].getReferencePayload(), "reference pass-through identity");
        assertTrue(rejectedChild[0].isRejected(), "rejection pass-through state");
        assertTrue(recovered[0].isFulfilled(), "rejection recovery state");
        assertTrue(recovered[0].getBooleanPayload(), "rejection recovery value");
        assertEquals(0, fixture.rejections.getUnhandled().size(), "handled propagation reports");
        pass();
    }

    private void testThrownReactionRejectsWithExactValue() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] destination = new JsPromise[1];
        JsPromise[] recovered = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            destination[0] = source.then((owner, settled, ignored) -> {
                throw JsThrownValue.bool(true);
            }, null);
            recovered[0] = destination[0].catchRejected((owner, rejected, next) -> {
                assertTrue(rejected.getBooleanPayload(), "exact thrown boolean");
                next.fulfillNumber(1.0);
            });
            source.fulfillVoid();
        });

        assertTrue(destination[0].isRejected(), "thrown reaction destination state");
        assertEquals(
                JsPromise.PAYLOAD_BOOLEAN,
                destination[0].getPayloadKind(),
                "thrown reaction payload kind");
        assertTrue(recovered[0].isFulfilled(), "thrown reaction recovery");
        pass();
    }

    private void testJavaReactionFailureRejectsByReference() throws Throwable {
        Fixture fixture = new Fixture();
        IllegalArgumentException expected = new IllegalArgumentException("java-failure");
        Object[] observed = new Object[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            JsPromise destination = source.then((owner, settled, ignored) -> {
                throw expected;
            }, null);
            destination.catchRejected((owner, rejected, next) -> {
                observed[0] = rejected.getReferencePayload();
            });
            source.fulfillVoid();
        });

        assertSame(expected, observed[0], "Java reaction error identity");
        pass();
    }

    private void testFirstSettlementWinsAndAdoptionLocks() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] first = new JsPromise[1];
        JsPromise[] adopted = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            first[0] = runtime.newPromise();
            assertTrue(first[0].fulfillNumber(1.0), "first settle accepted");
            assertTrue(!first[0].rejectNumber(2.0), "second settle ignored");

            JsPromise source = runtime.newPromise();
            adopted[0] = runtime.newPromise();
            assertTrue(adopted[0].resolveWith(source), "Promise adoption accepted");
            assertTrue(!adopted[0].fulfillNumber(3.0), "adoption locks resolution");
            source.fulfillNumber(9.0);
        });

        assertDoubleEquals(1.0, first[0].getNumberPayload(), "first settlement value");
        assertTrue(adopted[0].isFulfilled(), "adopted Promise state");
        assertDoubleEquals(9.0, adopted[0].getNumberPayload(), "adopted Promise value");
        pass();
    }

    private void testSettledPromiseAdoptionUsesMicrotask() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] destination = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            source.fulfillBoolean(true);
            destination[0] = runtime.newPromise();
            destination[0].resolveWith(source);
            assertTrue(destination[0].isPending(), "settled adoption must not run inline");
        });

        assertTrue(destination[0].isFulfilled(), "settled adoption destination state");
        assertTrue(destination[0].getBooleanPayload(), "settled adoption destination value");
        pass();
    }

    private void testSelfResolutionRejectsTypeError() throws Throwable {
        Fixture fixture = new Fixture();
        Object[] reason = new Object[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise promise = runtime.newPromise();
            promise.resolveWith(promise);
            promise.catchRejected((owner, rejected, destination) -> {
                reason[0] = rejected.getReferencePayload();
            });
        });

        assertTrue(reason[0] instanceof JsTypeError, "self-resolution rejection type");
        pass();
    }

    private void testUnhandledCheckpointAndLateHandle() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] promise = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            promise[0] = runtime.newPromise();
            promise[0].rejectReference("reason");
            assertEquals(0, fixture.rejections.getUnhandled().size(), "pre-checkpoint unhandled");
        });

        assertEquals(1, fixture.rejections.getUnhandled().size(), "checkpoint unhandled count");
        assertSame(promise[0], fixture.rejections.getUnhandled().get(0), "unhandled identity");

        runTurn(fixture.runtime, runtime -> {
            promise[0].catchRejected((owner, rejected, destination) -> {});
            assertEquals(1, fixture.rejections.getHandled().size(), "late handle timing");
        });

        assertSame(promise[0], fixture.rejections.getHandled().get(0), "late handled identity");
        pass();
    }

    private void testHandledBeforeCheckpointSuppressesUnhandled() throws Throwable {
        Fixture fixture = new Fixture();

        runTurn(fixture.runtime, runtime -> {
            JsPromise promise = runtime.newPromise();
            promise.rejectVoid();
            promise.catchRejected((owner, rejected, destination) -> {});
        });

        assertEquals(0, fixture.rejections.getUnhandled().size(), "handled-before-checkpoint report");
        pass();
    }

    private void testPropagationMovesUnhandledToChild() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] child = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            child[0] = source.then(null, null);
            source.rejectNumber(17.0);
        });

        assertEquals(1, fixture.rejections.getUnhandled().size(), "propagated unhandled count");
        assertSame(child[0], fixture.rejections.getUnhandled().get(0), "propagated unhandled owner");
        pass();
    }

    private void testPromiseRequiresOwnerAndActiveTurn() throws Throwable {
        Fixture fixture = new Fixture();
        Throwable idleFailure = null;
        try {
            fixture.runtime.newPromise();
        } catch (Throwable error) {
            idleFailure = error;
        }
        assertTrue(idleFailure instanceof IllegalStateException, "idle Promise allocation refusal");

        JsPromise[] promise = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> promise[0] = runtime.newPromise());

        AtomicReference<Throwable> foreignFailure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                promise[0].fulfillVoid();
            } catch (Throwable error) {
                foreignFailure.set(error);
            }
        }, "foreign-promise-settle");
        worker.start();
        worker.join();

        assertTrue(
                foreignFailure.get() instanceof IllegalStateException,
                "foreign Promise settlement refusal");
        pass();
    }

    private void testCrossRuntimeAdoptionIsRefused() throws Throwable {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        JsPromise[] firstPromise = new JsPromise[1];
        JsPromise[] secondPromise = new JsPromise[1];

        runTurn(first.runtime, runtime -> firstPromise[0] = runtime.newPromise());
        runTurn(second.runtime, runtime -> secondPromise[0] = runtime.newPromise());

        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        runTurn(first.runtime, runtime -> {
            try {
                firstPromise[0].resolveWith(secondPromise[0]);
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        assertTrue(failure.get() instanceof IllegalArgumentException, "cross-runtime adoption refusal");
        pass();
    }

    private void testUnresolvedReactionReturnFulfillsVoid() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] destination = new JsPromise[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            destination[0] = source.then((owner, settled, ignored) -> {}, null);
            source.fulfillVoid();
        });

        assertTrue(destination[0].isFulfilled(), "implicit undefined reaction result");
        assertEquals(JsPromise.PAYLOAD_VOID, destination[0].getPayloadKind(), "void result kind");
        pass();
    }

    private void testRejectionTrackerFailureIsReported() throws Throwable {
        ManualOwnerExecutor executor = new ManualOwnerExecutor();
        CollectingErrorReporter errors = new CollectingErrorReporter();
        IllegalStateException expected = new IllegalStateException("tracker-failure");
        PromiseRejectionTracker tracker = new PromiseRejectionTracker() {
            @Override
            public void onUnhandled(RuntimeInstance runtime, JsPromise promise) {
                throw expected;
            }

            @Override
            public void onHandled(RuntimeInstance runtime, JsPromise promise) {}
        };
        RuntimeInstance runtime = new RuntimeInstance(executor, errors, tracker);

        runTurn(runtime, owner -> owner.newPromise().rejectVoid());

        assertEquals(1, errors.getEntries().size(), "rejection tracker failure count");
        assertSame(
                RuntimeErrorPhase.REJECTION_TRACKER,
                errors.getEntries().get(0).getPhase(),
                "rejection tracker failure phase");
        assertSame(expected, errors.getEntries().get(0).getError(), "rejection tracker error identity");
        pass();
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask body) throws Throwable {
        runtime.enterHostTurn();
        try {
            body.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    private static RuntimeTask append(List<String> trace, String value) {
        return runtime -> trace.add(value);
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

    private static void assertListEquals(List<String> expected, List<String> actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RecordingPromiseRejectionTracker rejections =
                new RecordingPromiseRejectionTracker();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors, rejections);
    }
}
