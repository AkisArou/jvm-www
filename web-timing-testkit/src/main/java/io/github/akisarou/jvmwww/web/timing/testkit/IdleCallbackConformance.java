package io.github.akisarou.jvmwww.web.timing.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.testkit.ManualTimerHost;
import io.github.akisarou.jvmwww.web.timing.IdleCallbackHost;
import io.github.akisarou.jvmwww.web.timing.IdleCallbackScheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic Java 8 conformance for requestIdleCallback scheduling. */
public final class IdleCallbackConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new IdleCallbackConformance().run();
    }

    private void run() throws Throwable {
        orderingAndCheckpoint();
        deadlineBudget();
        timeoutTurns();
        idleWinsAndRearms();
        cancellationAndGeneration();
        failureRecovery();
        validationAndShutdown();
        ownerIsolation();
        System.out.println("Idle callback conformance: " + passed + " tests passed");
    }

    private void orderingAndCheckpoint() throws Throwable {
        Fixture f = new Fixture();
        IdleCallbackScheduler[] s = new IdleCallbackScheduler[1];
        List<String> trace = new ArrayList<String>();
        turn(f.runtime, runtime -> {
            s[0] = new IdleCallbackScheduler(runtime, f.host, f.callbackErrors);
            s[0].requestIdleCallback(deadline -> {
                trace.add("a");
                runtime.queueMicrotask(owner -> trace.add("micro"));
                s[0].requestIdleCallback(next -> trace.add("reposted"));
            });
            s[0].requestIdleCallback(deadline -> trace.add("b"));
            eq(1, f.host.getRequestCount());
        });
        f.host.fireAt(1_000_000L, 20_000_000L);
        eq(Arrays.asList("a", "micro"), trace);
        eq(2, f.host.getRequestCount());
        f.host.fireAt(2_000_000L, 20_000_000L);
        f.host.fireAt(3_000_000L, 20_000_000L);
        eq(Arrays.asList("a", "micro", "b", "reposted"), trace);
        f.runtime.close();
        passed++;
    }

    private void deadlineBudget() throws Throwable {
        Fixture f = new Fixture();
        f.host.setNowNanos(100_000_000L);
        double[] remaining = new double[3];
        boolean[] timedOut = new boolean[1];
        turn(f.runtime, runtime -> {
            IdleCallbackScheduler s = new IdleCallbackScheduler(runtime, f.host, f.callbackErrors);
            s.requestIdleCallback(deadline -> {
                timedOut[0] = deadline.isDidTimeout();
                remaining[0] = deadline.timeRemaining();
                f.host.advanceNanos(12_500_000L);
                remaining[1] = deadline.timeRemaining();
                f.host.advanceNanos(50_000_000L);
                remaining[2] = deadline.timeRemaining();
            });
        });
        f.host.fireAt(100_000_000L, 500_000_000L);
        yes(!timedOut[0]);
        eq(50.0, remaining[0]);
        eq(37.5, remaining[1]);
        eq(0.0, remaining[2]);
        f.runtime.close();
        passed++;
    }

    private void timeoutTurns() throws Throwable {
        Fixture f = new Fixture();
        List<String> trace = new ArrayList<String>();
        turn(f.runtime, runtime -> {
            IdleCallbackScheduler s = new IdleCallbackScheduler(runtime, f.host, f.callbackErrors);
            s.requestIdleCallback(deadline -> {
                yes(deadline.isDidTimeout());
                eq(0.0, deadline.timeRemaining());
                trace.add("t1");
                runtime.queueMicrotask(owner -> trace.add("m1"));
            }, 10.0);
            s.requestIdleCallback(deadline -> {
                trace.add("t2");
                runtime.queueMicrotask(owner -> trace.add("m2"));
            }, 10.0);
            s.requestIdleCallback(deadline -> trace.add("t3"), 20.0);
        });
        f.advanceMillis(10L);
        yes(f.timers.runOneDue());
        eq(Arrays.asList("t1", "m1"), trace);
        f.executor.runAll();
        eq(Arrays.asList("t1", "m1", "t2", "m2"), trace);
        eq(20_000_000L, f.timers.getArmedDeadlineNanos());
        f.advanceMillis(10L);
        yes(f.timers.runOneDue());
        eq(Arrays.asList("t1", "m1", "t2", "m2", "t3"), trace);
        yes(!f.host.hasPendingIdle());
        f.runtime.close();
        passed++;
    }

    private void idleWinsAndRearms() throws Throwable {
        Fixture f = new Fixture();
        List<String> trace = new ArrayList<String>();
        turn(f.runtime, runtime -> {
            IdleCallbackScheduler s = new IdleCallbackScheduler(runtime, f.host, f.callbackErrors);
            s.requestIdleCallback(deadline -> trace.add(deadline.isDidTimeout() ? "timeout" : "idle"), 10.0);
            s.requestIdleCallback(deadline -> trace.add("later"), 20.0);
        });
        f.host.fireAt(1_000_000L, 10_000_000L);
        eq(Arrays.asList("idle"), trace);
        yes(f.timers.isArmed());
        eq(19_000_000L, f.timers.getArmedDeadlineNanos());
        f.advanceMillis(19L);
        yes(f.timers.runOneDue());
        eq(Arrays.asList("idle", "later"), trace);
        f.runtime.close();
        passed++;
    }

    private void cancellationAndGeneration() throws Throwable {
        Fixture f = new Fixture();
        IdleCallbackScheduler[] s = new IdleCallbackScheduler[1];
        List<String> trace = new ArrayList<String>();
        double[] h = new double[3];
        turn(f.runtime, runtime -> {
            s[0] = new IdleCallbackScheduler(runtime, f.host, f.callbackErrors);
            h[0] = s[0].requestIdleCallback(deadline -> trace.add("old"), 5.0);
            s[0].cancelIdleCallback(h[0]);
            h[1] = s[0].requestIdleCallback(deadline -> trace.add("new"));
            yes(h[0] != h[1]);
            s[0].cancelIdleCallback(h[0]);
            s[0].cancelIdleCallback(Double.NaN);
            s[0].cancelIdleCallback(h[1] + 0.25);
        });
        f.host.fireAt(1_000_000L, 5_000_000L);
        eq(Arrays.asList("new"), trace);
        turn(f.runtime, runtime -> {
            h[2] = s[0].requestIdleCallback(deadline -> trace.add("never"));
            f.host.failNextCancel(new IllegalStateException("remove failed"));
            is(IllegalStateException.class, () -> s[0].cancelIdleCallback(h[2]));
        });
        yes(f.host.hasPendingIdle());
        f.host.fireAt(2_000_000L, 5_000_000L);
        eq(Arrays.asList("new"), trace);
        f.runtime.close();
        passed++;
    }

    private void failureRecovery() throws Throwable {
        Fixture f = new Fixture();
        IdleCallbackScheduler[] s = new IdleCallbackScheduler[1];
        List<String> trace = new ArrayList<String>();
        IllegalStateException callbackFailure = new IllegalStateException("idle boom");
        turn(f.runtime, runtime -> {
            s[0] = new IdleCallbackScheduler(runtime, f.host, f.callbackErrors);
            s[0].requestIdleCallback(deadline -> { throw callbackFailure; });
            s[0].requestIdleCallback(deadline -> trace.add("after"));
        });
        f.host.fireAt(1_000_000L, 5_000_000L);
        same(callbackFailure, f.callbackErrors.getErrors().get(0));
        f.host.fireAt(2_000_000L, 5_000_000L);
        eq(Arrays.asList("after"), trace);

        IllegalStateException hostFailure = new IllegalStateException("idle host stopped");
        turn(f.runtime, runtime -> {
            f.host.failNextRequest(hostFailure);
            same(hostFailure, capture(() -> s[0].requestIdleCallback(deadline -> trace.add("bad"), 10.0)));
            yes(!f.timers.isArmed());
            s[0].requestIdleCallback(deadline -> trace.add("recovered"));
        });
        f.host.fireAt(3_000_000L, 6_000_000L);
        eq(Arrays.asList("after", "recovered"), trace);
        f.runtime.close();
        passed++;
    }

    private void validationAndShutdown() throws Throwable {
        Fixture f = new Fixture();
        IdleCallbackHost.IdleCallback[] stale = new IdleCallbackHost.IdleCallback[1];
        turn(f.runtime, runtime -> {
            IdleCallbackScheduler s = new IdleCallbackScheduler(runtime, f.host, f.callbackErrors);
            is(IllegalArgumentException.class, () -> s.requestIdleCallback(deadline -> {}, -1.0));
            is(IllegalArgumentException.class, () -> s.requestIdleCallback(deadline -> {}, Double.NaN));
            is(IllegalArgumentException.class, () -> s.requestIdleCallback(deadline -> {}, Double.POSITIVE_INFINITY));
            is(IllegalArgumentException.class, () -> s.requestIdleCallback(deadline -> {}, 2_147_483_648.0));
            double zero = s.requestIdleCallback(deadline -> {}, 0.9);
            yes(!f.timers.isArmed());
            s.cancelIdleCallback(zero);
            s.requestIdleCallback(deadline -> { throw new AssertionError("closed runtime invoked callback"); }, 1.9);
            eq(1_000_000L, f.timers.getArmedDeadlineNanos());
            stale[0] = f.host.getPendingCallback();
        });
        f.runtime.close();
        yes(!f.host.hasPendingIdle());
        yes(!f.timers.isArmed());
        eq(2, f.host.getCancelCount());
        stale[0].onIdle(20_000_000L);
        passed++;
    }

    private void ownerIsolation() throws Throwable {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        IdleCallbackScheduler[] s = new IdleCallbackScheduler[1];
        turn(first.runtime, runtime -> s[0] = new IdleCallbackScheduler(runtime, first.host, first.callbackErrors));
        is(IllegalStateException.class, () -> s[0].requestIdleCallback(deadline -> {}));
        turn(second.runtime, runtime -> is(IllegalStateException.class, () -> s[0].requestIdleCallback(deadline -> {})));
        AtomicReference<Throwable> foreign = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> foreign.set(capture(() -> s[0].cancelIdleCallback(1.0))));
        worker.start();
        worker.join();
        yes(foreign.get() instanceof IllegalStateException);
        eq(0, first.host.getRequestCount());
        first.runtime.close();
        second.runtime.close();
        passed++;
    }

    private static void turn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try { task.execute(runtime); } finally { runtime.leaveHostTurn(); }
    }

    private static Throwable capture(Throwing action) {
        try { action.run(); return null; } catch (Throwable error) { return error; }
    }

    private static void yes(boolean value) { if (!value) throw new AssertionError(); }
    private static void same(Object expected, Object actual) { if (expected != actual) throw new AssertionError(); }
    private static void eq(int expected, int actual) { if (expected != actual) throw new AssertionError(expected + " != " + actual); }
    private static void eq(long expected, long actual) { if (expected != actual) throw new AssertionError(expected + " != " + actual); }
    private static void eq(double expected, double actual) { if (Double.compare(expected, actual) != 0) throw new AssertionError(expected + " != " + actual); }
    private static void eq(List<String> expected, List<String> actual) { if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
    private static void is(Class<? extends Throwable> type, Throwing action) {
        Throwable error = capture(action);
        if (!type.isInstance(error)) throw new AssertionError("Expected " + type.getName(), error);
    }

    private interface Throwing { void run() throws Throwable; }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter runtimeErrors = new CollectingErrorReporter();
        final ManualTimerHost timers = new ManualTimerHost();
        final ManualIdleCallbackHost host = new ManualIdleCallbackHost();
        final CollectingIdleCallbackExceptionReporter callbackErrors = new CollectingIdleCallbackExceptionReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, runtimeErrors, timers);

        void advanceMillis(long milliseconds) {
            timers.advanceMillis(milliseconds);
            host.advanceNanos(milliseconds * 1_000_000L);
        }
    }
}
