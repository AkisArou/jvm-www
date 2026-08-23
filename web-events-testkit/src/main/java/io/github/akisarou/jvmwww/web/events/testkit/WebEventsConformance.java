package io.github.akisarou.jvmwww.web.events.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsThrownValue;
import io.github.akisarou.jvmwww.runtime.OwnerExecutor;
import io.github.akisarou.jvmwww.runtime.PromiseRejectionTracker;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorReporter;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.runtime.TimerHost;
import io.github.akisarou.jvmwww.web.events.AbortAlgorithm;
import io.github.akisarou.jvmwww.web.events.AbortController;
import io.github.akisarou.jvmwww.web.events.AbortSignal;
import io.github.akisarou.jvmwww.web.events.AddEventListenerOptions;
import io.github.akisarou.jvmwww.web.events.CustomEvent;
import io.github.akisarou.jvmwww.web.events.DOMException;
import io.github.akisarou.jvmwww.web.events.Event;
import io.github.akisarou.jvmwww.web.events.EventExceptionReporter;
import io.github.akisarou.jvmwww.web.events.EventFailurePhase;
import io.github.akisarou.jvmwww.web.events.EventInit;
import io.github.akisarou.jvmwww.web.events.EventListener;
import io.github.akisarou.jvmwww.web.events.EventTarget;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free conformance tests for EventTarget and AbortSignal. */
public final class WebEventsConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new WebEventsConformance().run();
    }

    private void run() throws Throwable {
        testEventStateCancellationAndPassiveListeners();
        testCaptureOrderDuplicatesAndStopPropagation();
        testListenerMutationUsesStableDispatchCutoff();
        testOnceRecursiveDispatchAndImmediateStop();
        testListenerFailuresAreReportedAndDispatchContinues();
        testRecursiveSameEventIsInvalidState();
        testAbortControllerOrderReasonAndIdempotence();
        testAbortAlgorithmMutationAndFailureReporting();
        testSignalListenerOptionAndOnAbortPosition();
        testAnyRunsSourceStepsBeforeDependentSteps();
        testStaticAbortAnyAndTimeout();
        testOwnerTurnAndCrossRuntimeRestrictions();
        System.out.println("Web events conformance: " + passed + " tests passed");
    }

    private void testEventStateCancellationAndPassiveListeners() throws Throwable {
        Fixture fixture = new Fixture();
        EventTarget target = new EventTarget(fixture.runtime, fixture.eventErrors);
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            target.addEventListener(
                    "passive",
                    event -> {
                        trace.add("passive");
                        event.preventDefault();
                    },
                    new AddEventListenerOptions(false, true, false, null));
            Event passive = new Event("passive", new EventInit(false, true, false));
            assertTrue(target.dispatchEvent(passive), "passive listener cannot cancel");
            assertTrue(!passive.isDefaultPrevented(), "passive default prevention ignored");

            target.addEventListener(
                    "cancel",
                    event -> {
                        assertSame(target, event.getTarget(), "target during dispatch");
                        assertSame(target, event.getCurrentTarget(), "currentTarget during dispatch");
                        assertEquals(Event.AT_TARGET, event.getEventPhase(), "at-target phase");
                        assertEquals(1, event.composedPath().length, "active composed path");
                        event.preventDefault();
                        trace.add("cancel");
                    });
            Event cancel = new Event("cancel", new EventInit(false, true, true));
            assertTrue(!target.dispatchEvent(cancel), "canceled dispatch returns false");
            assertTrue(cancel.isDefaultPrevented(), "cancel flag retained after dispatch");
            assertSame(target, cancel.getTarget(), "target retained after dispatch");
            assertSame(null, cancel.getCurrentTarget(), "currentTarget cleared after dispatch");
            assertEquals(Event.NONE, cancel.getEventPhase(), "phase cleared after dispatch");
            assertEquals(0, cancel.composedPath().length, "path cleared after dispatch");
            assertTrue(cancel.isComposed(), "composed initialization");

            CustomEvent<String> custom = new CustomEvent<String>("custom", "detail");
            assertEquals("detail", custom.getDetail(), "custom event detail");
            custom.initCustomEvent("custom-2", true, true, "changed");
            assertEquals("custom-2", custom.getType(), "custom event reinitialization type");
            assertEquals("changed", custom.getDetail(), "custom event reinitialization detail");
        });

        assertListEquals(Arrays.asList("passive", "cancel"), trace, "event trace");
        fixture.close();
        pass();
    }

    private void testCaptureOrderDuplicatesAndStopPropagation() throws Throwable {
        Fixture fixture = new Fixture();
        EventTarget target = new EventTarget(fixture.runtime, fixture.eventErrors);
        List<String> trace = new ArrayList<String>();
        EventListener bubbleLate = event -> trace.add("bubble-late");
        EventListener capture = event -> {
            trace.add("capture");
            // DOM clones once per target/phase invocation: an addition during capture is excluded
            // from that capture pass but can participate in the later at-target bubble pass.
            target.addEventListener("ordered", bubbleLate);
        };

        runTurn(fixture.runtime, runtime -> {
            target.addEventListener("ordered", event -> trace.add("bubble-a"));
            target.addEventListener("ordered", capture, true);
            target.addEventListener(
                    "ordered",
                    capture,
                    new AddEventListenerOptions(true, true, true, null));
            target.addEventListener("ordered", event -> trace.add("bubble-b"));
            target.dispatchEvent(new Event("ordered"));

            target.addEventListener(
                    "stopped",
                    event -> {
                        trace.add("capture-stop");
                        event.stopPropagation();
                    },
                    true);
            target.addEventListener("stopped", event -> trace.add("capture-later"), true);
            target.addEventListener("stopped", event -> trace.add("bubble-skipped"));
            target.dispatchEvent(new Event("stopped"));
        });

        assertListEquals(
                Arrays.asList(
                        "capture",
                        "bubble-a",
                        "bubble-b",
                        "bubble-late",
                        "capture-stop",
                        "capture-later"),
                trace,
                "capture grouping, duplicate suppression, and propagation stop");
        fixture.close();
        pass();
    }

    private void testListenerMutationUsesStableDispatchCutoff() throws Throwable {
        Fixture fixture = new Fixture();
        EventTarget target = new EventTarget(fixture.runtime, fixture.eventErrors);
        List<String> trace = new ArrayList<String>();
        EventListener late = event -> trace.add("late");
        EventListener second = event -> trace.add("second-removed");
        EventListener[] first = new EventListener[1];
        first[0] = event -> {
            trace.add("first");
            target.removeEventListener("mutate", second);
            target.addEventListener("mutate", late);
        };

        runTurn(fixture.runtime, runtime -> {
            target.addEventListener("mutate", first[0]);
            target.addEventListener("mutate", second);
            target.addEventListener("mutate", event -> trace.add("third"));
            target.dispatchEvent(new Event("mutate"));
            target.dispatchEvent(new Event("mutate"));
        });

        assertListEquals(
                Arrays.asList("first", "third", "first", "third", "late"),
                trace,
                "removed listeners are skipped and newly added listeners wait for next dispatch");
        fixture.close();
        pass();
    }

    private void testOnceRecursiveDispatchAndImmediateStop() throws Throwable {
        Fixture fixture = new Fixture();
        EventTarget target = new EventTarget(fixture.runtime, fixture.eventErrors);
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            target.addEventListener(
                    "recursive",
                    event -> {
                        trace.add("once");
                        target.dispatchEvent(new Event("recursive"));
                    },
                    new AddEventListenerOptions(false, false, true, null));
            target.addEventListener("recursive", event -> trace.add("regular"));
            target.dispatchEvent(new Event("recursive"));

            target.addEventListener(
                    "immediate",
                    event -> {
                        trace.add("immediate-first");
                        event.stopImmediatePropagation();
                    });
            target.addEventListener("immediate", event -> trace.add("immediate-skipped"));
            target.dispatchEvent(new Event("immediate"));
        });

        assertListEquals(
                Arrays.asList("once", "regular", "regular", "immediate-first"),
                trace,
                "once removal precedes recursion and immediate stop suppresses later listeners");
        fixture.close();
        pass();
    }

    private void testListenerFailuresAreReportedAndDispatchContinues() throws Throwable {
        Fixture fixture = new Fixture();
        EventTarget target = new EventTarget(fixture.runtime, fixture.eventErrors);
        IllegalStateException expected = new IllegalStateException("listener-failure");
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            target.addEventListener("failure", event -> { throw expected; });
            target.addEventListener("failure", event -> trace.add("after-failure"));
            assertTrue(target.dispatchEvent(new Event("failure")), "listener failure not propagated");
        });

        assertListEquals(Arrays.asList("after-failure"), trace, "later listener still runs");
        assertEquals(1, fixture.eventErrors.entries.size(), "reported listener error count");
        assertSame(
                EventFailurePhase.EVENT_LISTENER,
                fixture.eventErrors.entries.get(0).phase,
                "listener failure phase");
        assertSame(expected, fixture.eventErrors.entries.get(0).error, "listener failure identity");
        fixture.close();
        pass();
    }

    private void testRecursiveSameEventIsInvalidState() throws Throwable {
        Fixture fixture = new Fixture();
        EventTarget target = new EventTarget(fixture.runtime, fixture.eventErrors);
        Event event = new Event("same-event");
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            target.addEventListener("same-event", ignored -> target.dispatchEvent(event));
            target.addEventListener("same-event", ignored -> trace.add("continued"));
            target.dispatchEvent(event);
        });

        assertListEquals(Arrays.asList("continued"), trace, "same-event failure is reported");
        assertEquals(1, fixture.eventErrors.entries.size(), "recursive dispatch report count");
        Throwable error = fixture.eventErrors.entries.get(0).error;
        assertTrue(error instanceof DOMException, "recursive dispatch error type");
        assertEquals("InvalidStateError", ((DOMException) error).getName(), "DOMException name");
        fixture.close();
        pass();
    }

    private void testAbortControllerOrderReasonAndIdempotence() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        Object reason = new Object();
        AbortController[] controller = new AbortController[1];

        runTurn(fixture.runtime, runtime -> {
            controller[0] = new AbortController(runtime, fixture.eventErrors);
            AbortSignal signal = controller[0].getSignal();
            signal.addAbortAlgorithm(source -> trace.add("algorithm-a"));
            signal.addAbortAlgorithm(source -> trace.add("algorithm-b"));
            signal.addEventListener("abort", event -> trace.add("event"));

            trace.add("before");
            controller[0].abortReference(reason);
            trace.add("after");
            assertTrue(signal.isAborted(), "signal aborted synchronously");
            assertEquals(JsPromise.PAYLOAD_REFERENCE, signal.getReasonKind(), "reference reason kind");
            assertSame(reason, signal.getReasonReference(), "reference reason identity");
            Throwable thrown = capture(signal::throwIfAborted);
            assertTrue(thrown instanceof JsThrownValue, "throwIfAborted carrier");
            assertSame(
                    reason,
                    ((JsThrownValue) thrown).getReferencePayload(),
                    "throwIfAborted exact reason");
            controller[0].abortNumber(7.0);
            assertSame(reason, signal.getReasonReference(), "second abort is ignored");

            AbortController undefined = new AbortController(runtime, fixture.eventErrors);
            undefined.abortThrown(JsThrownValue.voidValue());
            Object undefinedReason = undefined.getSignal().getReasonReference();
            assertTrue(undefinedReason instanceof DOMException, "undefined abort gets default reason");
            assertEquals(
                    "AbortError",
                    ((DOMException) undefinedReason).getName(),
                    "undefined abort reason name");
        });

        assertListEquals(
                Arrays.asList("before", "algorithm-a", "algorithm-b", "event", "after"),
                trace,
                "abort algorithms precede synchronous event and return");
        fixture.close();
        pass();
    }

    private void testAbortAlgorithmMutationAndFailureReporting() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        IllegalStateException expected = new IllegalStateException("abort-algorithm-failure");
        AbortController[] controller = new AbortController[1];
        AbortAlgorithm[] removed = new AbortAlgorithm[1];

        runTurn(fixture.runtime, runtime -> {
            controller[0] = new AbortController(runtime, fixture.eventErrors);
            AbortSignal signal = controller[0].getSignal();
            removed[0] = source -> trace.add("removed");
            signal.addAbortAlgorithm(source -> {
                trace.add("first");
                source.removeAbortAlgorithm(removed[0]);
                throw expected;
            });
            signal.addAbortAlgorithm(removed[0]);
            signal.addAbortAlgorithm(source -> trace.add("third"));
            signal.addEventListener("abort", event -> trace.add("event"));
            controller[0].abort();
        });

        assertListEquals(Arrays.asList("first", "third", "event"), trace, "algorithm mutation");
        assertEquals(1, fixture.eventErrors.entries.size(), "abort algorithm report count");
        assertSame(
                EventFailurePhase.ABORT_ALGORITHM,
                fixture.eventErrors.entries.get(0).phase,
                "abort algorithm failure phase");
        assertSame(expected, fixture.eventErrors.entries.get(0).error, "algorithm failure identity");
        fixture.close();
        pass();
    }

    private void testSignalListenerOptionAndOnAbortPosition() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            EventTarget target = new EventTarget(runtime, fixture.eventErrors);
            AbortController remover = new AbortController(runtime, fixture.eventErrors);
            target.addEventListener(
                    "work",
                    event -> trace.add("removed-listener"),
                    new AddEventListenerOptions(false, false, false, remover.getSignal()));
            remover.abort();
            target.dispatchEvent(new Event("work"));

            target.addEventListener(
                    "work",
                    event -> trace.add("never-added"),
                    new AddEventListenerOptions(false, false, false, remover.getSignal()));
            target.dispatchEvent(new Event("work"));

            AbortController order = new AbortController(runtime, fixture.eventErrors);
            AbortSignal signal = order.getSignal();
            signal.addEventListener("abort", event -> trace.add("a"));
            signal.setOnAbort(event -> trace.add("handler-1"));
            signal.addEventListener("abort", event -> trace.add("b"));
            signal.setOnAbort(event -> trace.add("handler-2"));
            order.abort();
        });

        assertListEquals(
                Arrays.asList("a", "handler-2", "b"),
                trace,
                "signal option removes listeners and onabort keeps its position");
        fixture.close();
        pass();
    }

    private void testAnyRunsSourceStepsBeforeDependentSteps() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            AbortController first = new AbortController(runtime, fixture.eventErrors);
            AbortController second = new AbortController(runtime, fixture.eventErrors);
            first.getSignal().addAbortAlgorithm(source -> trace.add("source-algorithm"));
            first.getSignal().addEventListener("abort", event -> trace.add("source-event"));

            AbortSignal dependent =
                    AbortSignal.anyWithReporter(
                            runtime,
                            fixture.eventErrors,
                            first.getSignal(),
                            second.getSignal());
            dependent.addAbortAlgorithm(source -> trace.add("dependent-algorithm"));
            dependent.addEventListener("abort", event -> trace.add("dependent-event"));
            AbortSignal nested =
                    AbortSignal.anyWithReporter(
                            runtime,
                            fixture.eventErrors,
                            dependent);
            nested.addAbortAlgorithm(source -> trace.add("nested-algorithm"));
            nested.addEventListener("abort", event -> trace.add("nested-event"));

            first.abortNumber(7.0);
            assertTrue(dependent.isAborted(), "dependent signal aborted");
            assertEquals(JsPromise.PAYLOAD_NUMBER, dependent.getReasonKind(), "dependent reason kind");
            assertDoubleEquals(7.0, dependent.getReasonNumber(), "dependent reason value");
            assertDoubleEquals(7.0, nested.getReasonNumber(), "flattened dependent reason value");
            second.abortBoolean(true);
        });

        assertListEquals(
                Arrays.asList(
                        "source-algorithm",
                        "source-event",
                        "dependent-algorithm",
                        "dependent-event",
                        "nested-algorithm",
                        "nested-event"),
                trace,
                "source abort steps precede dependent abort steps");
        fixture.close();
        pass();
    }

    private void testStaticAbortAnyAndTimeout() throws Throwable {
        Fixture fixture = new Fixture();
        List<String> trace = new ArrayList<String>();
        AbortSignal[] timeoutSignal = new AbortSignal[1];
        AbortSignal[] zeroSignal = new AbortSignal[1];

        runTurn(fixture.runtime, runtime -> {
            AbortSignal already = AbortSignal.abortWithReporter(runtime, fixture.eventErrors);
            assertTrue(already.isAborted(), "static abort state");
            Object defaultReason = already.getReasonReference();
            assertTrue(defaultReason instanceof DOMException, "static abort default reason type");
            assertEquals(
                    "AbortError",
                    ((DOMException) defaultReason).getName(),
                    "static abort default reason name");
            already.addEventListener("abort", event -> trace.add("late-static-event"));

            AbortSignal number = AbortSignal.abortNumber(runtime, 3.0);
            AbortSignal reference = AbortSignal.abortReference(runtime, new Object());
            AbortSignal firstWins =
                    AbortSignal.anyWithReporter(runtime, fixture.eventErrors, number, reference);
            assertDoubleEquals(3.0, firstWins.getReasonNumber(), "first aborted input reason");

            AbortSignal zero = AbortSignal.timeoutWithReporter(runtime, 0.9, fixture.eventErrors);
            zeroSignal[0] = zero;
            zero.addEventListener("abort", event -> trace.add("zero-timeout-event"));

            AbortSignal timeout = AbortSignal.timeoutWithReporter(runtime, 1.8, fixture.eventErrors);
            timeoutSignal[0] = timeout;
            assertTrue(timeout instanceof RuntimeTask, "timeout signal is its own RuntimeTask");
            timeout.addEventListener("abort", event -> {
                trace.add("timeout-event");
                runtime.queueMicrotask(owner -> trace.add("timeout-microtask"));
            });
            assertTrue(!timeout.isAborted(), "timeout remains pending before deadline");
        });

        fixture.executor.runNext();
        runTurn(fixture.runtime, runtime ->
                assertTrue(zeroSignal[0].isAborted(), "zero active-time timeout is asynchronous"));

        fixture.timers.advanceMillis(1L);
        assertTrue(fixture.timers.runOneDue(), "timeout timer due");

        runTurn(fixture.runtime, runtime -> {
            assertListEquals(
                    Arrays.asList(
                            "zero-timeout-event",
                            "timeout-event",
                            "timeout-microtask"),
                    trace,
                    "timeout abort event receives a complete microtask checkpoint");
            assertTrue(timeoutSignal[0].isAborted(), "timeout signal aborted at deadline");
            Object reason = timeoutSignal[0].getReasonReference();
            assertTrue(reason instanceof DOMException, "timeout reason type");
            assertEquals("TimeoutError", ((DOMException) reason).getName(), "timeout reason name");
        });
        fixture.close();
        pass();
    }

    private void testOwnerTurnAndCrossRuntimeRestrictions() throws Throwable {
        Fixture fixture = new Fixture();
        EventTarget[] target = new EventTarget[1];
        AbortSignal[] signal = new AbortSignal[1];
        runTurn(fixture.runtime, runtime -> {
            target[0] = new EventTarget(runtime, fixture.eventErrors);
            signal[0] = new AbortController(runtime, fixture.eventErrors).getSignal();
        });

        Throwable idle = capture(() -> target[0].dispatchEvent(new Event("idle")));
        assertTrue(idle instanceof IllegalStateException, "idle dispatch refusal");

        AtomicReference<Throwable> foreign = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                target[0].addEventListener("foreign", event -> {});
            } catch (Throwable error) {
                foreign.set(error);
            }
        }, "web-events-foreign-access");
        worker.start();
        worker.join();
        assertTrue(foreign.get() instanceof IllegalStateException, "foreign listener mutation refusal");

        Fixture other = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Throwable mismatch = capture(() -> target[0].addEventListener(
                    "cross",
                    event -> {},
                    new AddEventListenerOptions(false, false, false, otherSignal(other))));
            assertTrue(mismatch instanceof IllegalArgumentException, "cross-runtime signal refusal");
            Throwable anyMismatch = capture(() -> AbortSignal.any(runtime, signal[0], otherSignal(other)));
            assertTrue(anyMismatch instanceof IllegalArgumentException, "cross-runtime any refusal");
        });

        fixture.close();
        other.close();
        pass();
    }

    private static AbortSignal otherSignal(Fixture fixture) throws Throwable {
        AbortSignal[] result = new AbortSignal[1];
        runTurn(fixture.runtime, runtime ->
                result[0] = new AbortController(runtime, fixture.eventErrors).getSignal());
        return result[0];
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

    private static void assertEquals(short expected, short actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertDoubleEquals(double expected, double actual, String label) {
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
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
        final ManualTimerHost timers = new ManualTimerHost();
        final CollectingRuntimeErrors runtimeErrors = new CollectingRuntimeErrors();
        final RecordingEventReporter eventErrors = new RecordingEventReporter();
        final RuntimeInstance runtime =
                new RuntimeInstance(
                        executor,
                        runtimeErrors,
                        PromiseRejectionTracker.NONE,
                        64,
                        timers);

        void close() {
            if (!runtime.isClosed()) {
                runtime.close();
            }
        }
    }

    private static final class ManualOwnerExecutor implements OwnerExecutor {
        private final Thread owner = Thread.currentThread();
        private final ArrayDeque<Runnable> callbacks = new ArrayDeque<Runnable>();

        @Override
        public boolean isOwnerThread() {
            return Thread.currentThread() == owner;
        }

        @Override
        public synchronized void post(Runnable callback) {
            callbacks.addLast(callback);
        }

        void runNext() {
            final Runnable callback;
            synchronized (this) {
                callback = callbacks.pollFirst();
            }
            if (callback == null) {
                throw new AssertionError("No owner callback is pending");
            }
            callback.run();
        }
    }

    private static final class ManualTimerHost implements TimerHost {
        private long nowNanos;
        private long deadlineNanos;
        private Runnable callback;

        @Override
        public long nowNanos() {
            return nowNanos;
        }

        @Override
        public void arm(long deadlineNanos, Runnable wakeCallback) {
            this.deadlineNanos = deadlineNanos;
            callback = wakeCallback;
        }

        @Override
        public void disarm() {
            callback = null;
        }

        void advanceMillis(long milliseconds) {
            nowNanos += milliseconds * 1_000_000L;
        }

        boolean runOneDue() {
            if (callback == null || deadlineNanos > nowNanos) {
                return false;
            }
            Runnable due = callback;
            callback = null;
            due.run();
            return true;
        }
    }

    private static final class CollectingRuntimeErrors implements RuntimeErrorReporter {
        final List<Throwable> errors = new ArrayList<Throwable>();

        @Override
        public void report(RuntimeInstance runtime, RuntimeErrorPhase phase, Throwable error) {
            errors.add(error);
        }
    }

    private static final class ReporterEntry {
        final EventFailurePhase phase;
        final Throwable error;

        ReporterEntry(EventFailurePhase phase, Throwable error) {
            this.phase = phase;
            this.error = error;
        }
    }

    private static final class RecordingEventReporter implements EventExceptionReporter {
        final List<ReporterEntry> entries = new ArrayList<ReporterEntry>();

        @Override
        public void report(RuntimeInstance runtime, EventFailurePhase phase, Throwable error) {
            entries.add(new ReporterEntry(phase, error));
        }
    }
}
