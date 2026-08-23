package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.AsyncFrame;
import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsThrownValue;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dependency-free conformance tests for the compiler-facing fused async-frame ABI. */
public final class AsyncFrameConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        AsyncFrameConformance suite = new AsyncFrameConformance();
        suite.run();
    }

    private void run() throws Throwable {
        testEagerPrefixAndPendingAwait();
        testSettledAwaitStillUsesMicrotask();
        testSequentialAwaitsReuseOneFrame();
        testAwaitRejectionCanBeCaughtAtResumeSite();
        testUncaughtAwaitRejectionPreservesExactReason();
        testSynchronousThrowRejectsInsteadOfEscaping();
        testResultAdoptionUsesTheFrameAsTheJob();
        testThrowAfterStagedSuspendLeavesNoSubscription();
        testStateWithoutTerminalActionRejectsPrecisely();
        testSelfAdoptionRejectsTypeError();

        System.out.println("Async-frame conformance: " + passed + " tests passed");
    }

    private void testEagerPrefixAndPendingAwait() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        JsPromise[] source = new JsPromise[1];
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            source[0] = runtime.newPromise();
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {
                    if (state == INITIAL_STATE) {
                        trace.add("prefix");
                        suspendOn(source[0], 1);
                        return;
                    }
                    if (state == 1) {
                        trace.add("resume");
                        fulfillNumber(awaitNumber(awaited) + 1.0);
                        return;
                    }
                    throw new AssertionError("unexpected state " + state);
                }
            };

            AsyncFrame result = frame[0].start();
            assertSame(frame[0], result, "async result is the frame");
            assertTrue(frame[0].isPending(), "pending source suspends frame");
            trace.add("caller");
        });

        assertListEquals(Arrays.asList("prefix", "caller"), trace, "eager prefix ordering");

        runTurn(fixture.runtime, runtime -> {
            assertTrue(source[0].fulfillNumber(41.0), "source settlement");
            trace.add("host-end");
        });

        assertListEquals(
                Arrays.asList("prefix", "caller", "host-end", "resume"),
                trace,
                "pending await resumes after host body");
        assertTrue(frame[0].isFulfilled(), "pending await result state");
        assertDoubleEquals(42.0, frame[0].getNumberPayload(), "pending await result value");
        assertEquals(0, fixture.errors.getEntries().size(), "pending await runtime errors");
        pass();
    }

    private void testSettledAwaitStillUsesMicrotask() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            source.fulfillVoid();
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {
                    if (state == INITIAL_STATE) {
                        trace.add("prefix");
                        suspendOn(source, 1);
                        return;
                    }
                    if (state == 1) {
                        awaitVoid(awaited);
                        trace.add("resume");
                        fulfillVoid();
                        return;
                    }
                    throw new AssertionError("unexpected state " + state);
                }
            };

            frame[0].start();
            assertTrue(frame[0].isPending(), "settled await remains asynchronous");
            trace.add("caller");
        });

        assertListEquals(
                Arrays.asList("prefix", "caller", "resume"),
                trace,
                "settled await microtask ordering");
        assertTrue(frame[0].isFulfilled(), "settled await frame state");
        pass();
    }

    private void testSequentialAwaitsReuseOneFrame() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        JsPromise[] firstSource = new JsPromise[1];
        JsPromise[] secondSource = new JsPromise[1];
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            firstSource[0] = runtime.newPromise();
            secondSource[0] = runtime.newPromise();
            frame[0] = new AsyncFrame(runtime) {
                private double first;

                @Override
                protected void executeState(int state, JsPromise awaited) {
                    if (state == INITIAL_STATE) {
                        suspendOn(firstSource[0], 1);
                        return;
                    }
                    if (state == 1) {
                        first = awaitNumber(awaited);
                        trace.add("first");
                        suspendOn(secondSource[0], 2);
                        return;
                    }
                    if (state == 2) {
                        trace.add("second");
                        fulfillNumber(first + awaitNumber(awaited));
                        return;
                    }
                    throw new AssertionError("unexpected state " + state);
                }
            };
            assertSame(frame[0], frame[0].start(), "sequential result is one frame");
        });

        runTurn(fixture.runtime, runtime -> firstSource[0].fulfillNumber(10.0));
        assertListEquals(Arrays.asList("first"), trace, "first sequential resume");
        assertTrue(frame[0].isPending(), "frame re-suspended for second await");

        runTurn(fixture.runtime, runtime -> secondSource[0].fulfillNumber(5.0));
        assertListEquals(Arrays.asList("first", "second"), trace, "second sequential resume");
        assertDoubleEquals(15.0, frame[0].getNumberPayload(), "sequential await result");
        assertEquals(0, fixture.errors.getEntries().size(), "sequential await runtime errors");
        pass();
    }

    private void testAwaitRejectionCanBeCaughtAtResumeSite() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] source = new JsPromise[1];
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            source[0] = runtime.newPromise();
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {
                    if (state == INITIAL_STATE) {
                        suspendOn(source[0], 1);
                        return;
                    }
                    try {
                        awaitBoolean(awaited);
                        throw new AssertionError("rejected await returned normally");
                    } catch (JsThrownValue reason) {
                        assertEquals(
                                JsPromise.PAYLOAD_BOOLEAN,
                                reason.getPayloadKind(),
                                "caught await reason kind");
                        assertTrue(reason.getBooleanPayload(), "caught await reason value");
                        fulfillReference("caught");
                    }
                }
            };
            frame[0].start();
        });

        runTurn(fixture.runtime, runtime -> source[0].rejectBoolean(true));

        assertTrue(frame[0].isFulfilled(), "caught rejection fulfills frame");
        assertEquals("caught", frame[0].getReferencePayload(), "caught rejection result");
        pass();
    }

    private void testUncaughtAwaitRejectionPreservesExactReason() throws Throwable {
        Fixture fixture = new Fixture();
        Object marker = new Object();
        JsPromise[] source = new JsPromise[1];
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            source[0] = runtime.newPromise();
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {
                    if (state == INITIAL_STATE) {
                        suspendOn(source[0], 1);
                        return;
                    }
                    awaitReference(awaited);
                    fulfillVoid();
                }
            };
            frame[0].start();
        });

        runTurn(fixture.runtime, runtime -> source[0].rejectReference(marker));

        assertTrue(frame[0].isRejected(), "uncaught await rejects frame");
        assertSame(marker, frame[0].getReferencePayload(), "uncaught await exact reason");
        pass();
    }

    private void testSynchronousThrowRejectsInsteadOfEscaping() throws Throwable {
        Fixture fixture = new Fixture();
        AsyncFrame[] frame = new AsyncFrame[1];
        Throwable[] escaped = new Throwable[1];

        runTurn(fixture.runtime, runtime -> {
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {
                    throw JsThrownValue.number(7.0);
                }
            };
            try {
                frame[0].start();
            } catch (Throwable error) {
                escaped[0] = error;
            }
        });

        assertSame(null, escaped[0], "async synchronous throw must not escape");
        assertTrue(frame[0].isRejected(), "async synchronous throw state");
        assertDoubleEquals(7.0, frame[0].getNumberPayload(), "async synchronous throw reason");
        pass();
    }

    private void testResultAdoptionUsesTheFrameAsTheJob() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            JsPromise source = runtime.newPromise();
            source.fulfillNumber(33.0);
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {
                    if (state != INITIAL_STATE) {
                        throw new AssertionError("adoption must not enter an await state");
                    }
                    trace.add("prefix");
                    adoptResult(source);
                }
            };

            assertSame(frame[0], frame[0].start(), "adoption result is the frame");
            assertTrue(frame[0].isPending(), "settled adoption still queues a job");
            trace.add("caller");
        });

        assertListEquals(
                Arrays.asList("prefix", "caller"),
                trace,
                "adoption does not add a synchronous resume");
        assertTrue(frame[0].isFulfilled(), "adopted frame result state");
        assertDoubleEquals(33.0, frame[0].getNumberPayload(), "adopted frame result value");
        pass();
    }

    private void testThrowAfterStagedSuspendLeavesNoSubscription() throws Throwable {
        Fixture fixture = new Fixture();
        Object marker = new Object();
        JsPromise[] source = new JsPromise[1];
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            source[0] = runtime.newPromise();
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {
                    if (state == INITIAL_STATE) {
                        suspendOn(source[0], 1);
                        throw JsThrownValue.reference(marker);
                    }
                    throw new AssertionError("stale subscription resumed state " + state);
                }
            };
            frame[0].start();
            frame[0].catchRejected((owner, rejected, destination) -> {});
        });

        assertTrue(frame[0].isRejected(), "staged-suspend throw rejects frame");
        assertSame(marker, frame[0].getReferencePayload(), "staged-suspend throw reason");

        runTurn(fixture.runtime, runtime -> source[0].fulfillVoid());

        assertEquals(0, fixture.errors.getEntries().size(), "stale suspension must not execute");
        pass();
    }

    private void testStateWithoutTerminalActionRejectsPrecisely() throws Throwable {
        Fixture fixture = new Fixture();
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {}
            };
            frame[0].start();
            frame[0].catchRejected((owner, rejected, destination) -> {});
        });

        assertTrue(frame[0].isRejected(), "missing terminal action rejects frame");
        Object reason = frame[0].getReferencePayload();
        assertTrue(reason instanceof IllegalStateException, "missing action reason type");
        assertTrue(
                ((IllegalStateException) reason).getMessage().contains("without suspending or resolving"),
                "missing action diagnostic");
        pass();
    }

    private void testSelfAdoptionRejectsTypeError() throws Throwable {
        Fixture fixture = new Fixture();
        AsyncFrame[] frame = new AsyncFrame[1];

        runTurn(fixture.runtime, runtime -> {
            frame[0] = new AsyncFrame(runtime) {
                @Override
                protected void executeState(int state, JsPromise awaited) {
                    adoptResult(this);
                }
            };
            frame[0].start();
            frame[0].catchRejected((owner, rejected, destination) -> {});
        });

        assertTrue(frame[0].isRejected(), "self-adoption rejects frame");
        assertTrue(frame[0].getReferencePayload() instanceof JsTypeError, "self-adoption reason");
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

    private void pass() {
        passed++;
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertDoubleEquals(double expected, double actual, String label) {
        if (Double.compare(expected, actual) != 0) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertListEquals(
            List<String> expected,
            List<String> actual,
            String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": values are not identical");
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
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }
}
