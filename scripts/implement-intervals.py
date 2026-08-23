from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADR = ROOT / "docs/decisions/0003-reuse-interval-entry.md"

if ADR.exists():
    print("Interval slice is already present; nothing to apply")
    raise SystemExit(0)


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"Expected one match in {relative_path}, found {count}: {old[:80]!r}"
        )
    path.write_text(text.replace(old, new), encoding="utf-8")


# ---------------------------------------------------------------------------
# Runtime timer core
# ---------------------------------------------------------------------------

replace_once(
    "runtime-core/src/main/java/io/github/akisarou/jvmwww/runtime/RuntimeTimerQueue.java",
    "/** Owner-confined logical one-shot timer heap. */",
    "/** Owner-confined logical timeout and interval heap. */",
)

replace_once(
    "runtime-core/src/main/java/io/github/akisarou/jvmwww/runtime/RuntimeTimerQueue.java",
    '''    double setTimeout(RuntimeTask callback, double delayMilliseconds) {
        runtime.assertLanguageExecution();
        ensureOpen();
        Objects.requireNonNull(callback, "callback");

        long nowNanos = readNowNanos();
        long delayMillis = coerceDelayMillis(delayMilliseconds);
        TimerEntry entry = allocateSlot();
        entry.deadlineNanos = saturatingAdd(nowNanos, delayMillis * NANOS_PER_MILLI);
        entry.sequence = allocateSequence();
        entry.callback = callback;
        push(entry);

        if (!drainingWake) {
            synchronizeAlarm();
        }
        return (double) encodeHandle(entry);
    }

    void clearTimeout(double handle) {
        runtime.assertLanguageExecution();
        if (closed) {
            return;
        }

        TimerEntry entry = lookupHandle(handle);
        if (entry == null) {
            return;
        }

        removeAt(entry.heapIndex);
        RuntimeTask callback = entry.callback;
        releaseSlot(entry);
        runtime.discardTask(callback);

        if (!drainingWake) {
            synchronizeAlarm();
        }
    }
''',
    '''    double setTimeout(RuntimeTask callback, double delayMilliseconds) {
        return schedule(callback, delayMilliseconds, false);
    }

    double setInterval(RuntimeTask callback, double delayMilliseconds) {
        return schedule(callback, delayMilliseconds, true);
    }

    void clearTimeout(double handle) {
        clearTimer(handle);
    }

    void clearInterval(double handle) {
        clearTimer(handle);
    }

    private double schedule(
            RuntimeTask callback,
            double delayMilliseconds,
            boolean repeating) {
        runtime.assertLanguageExecution();
        ensureOpen();
        Objects.requireNonNull(callback, "callback");

        long nowNanos = readNowNanos();
        long delayMillis = coerceDelayMillis(delayMilliseconds);
        TimerEntry entry = allocateSlot();
        entry.deadlineNanos = saturatingAdd(nowNanos, delayMillis * NANOS_PER_MILLI);
        entry.sequence = allocateSequence();
        entry.callback = callback;
        entry.repeatMillis = repeating ? delayMillis : 0L;
        push(entry);

        if (!drainingWake) {
            synchronizeAlarm();
        }
        return (double) encodeHandle(entry);
    }

    private void clearTimer(double handle) {
        runtime.assertLanguageExecution();
        if (closed) {
            return;
        }

        TimerEntry entry = lookupHandle(handle);
        if (entry == null) {
            return;
        }

        if (entry.firing) {
            entry.cancelledWhileFiring = true;
            return;
        }

        removeAt(entry.heapIndex);
        RuntimeTask callback = entry.callback;
        releaseSlot(entry);
        runtime.discardTask(callback);

        if (!drainingWake) {
            synchronizeAlarm();
        }
    }
''',
)

replace_once(
    "runtime-core/src/main/java/io/github/akisarou/jvmwww/runtime/RuntimeTimerQueue.java",
    '''                TimerEntry due = removeAt(0);
                RuntimeTask callback = due.callback;
                releaseSlot(due);
                runtime.executeHostTask(callback);
                processed++;
''',
    '''                TimerEntry due = removeAt(0);
                if (due.repeatMillis == 0L) {
                    // Preserve the established one-shot path: a fired timeout releases its handle
                    // before the callback, so a nested registration may reuse the slot immediately.
                    RuntimeTask callback = due.callback;
                    releaseSlot(due);
                    runtime.executeHostTask(callback);
                } else {
                    // The interval entry itself is the runtime job. It remains generation-valid
                    // while the callback runs so a synchronous clearInterval can mark this exact
                    // firing as cancelled without allocating a wrapper node.
                    due.firing = true;
                    due.cancelledWhileFiring = false;
                    runtime.executeHostTask(due);
                }
                processed++;
''',
)

replace_once(
    "runtime-core/src/main/java/io/github/akisarou/jvmwww/runtime/RuntimeTimerQueue.java",
    '''    private void synchronizeAlarm() {
''',
    '''    private void finishFiring(TimerEntry entry, boolean callbackCompleted) {
        if (!entry.firing) {
            throw new IllegalStateException("Timer entry completed outside its firing turn");
        }
        entry.firing = false;

        boolean rescheduled = false;
        try {
            if (entry.repeatMillis != 0L
                    && callbackCompleted
                    && !entry.cancelledWhileFiring
                    && !closed
                    && !runtime.isClosed()) {
                long nowNanos = readNowNanos();
                entry.deadlineNanos =
                        saturatingAdd(nowNanos, entry.repeatMillis * NANOS_PER_MILLI);
                entry.sequence = allocateSequence();
                push(entry);
                rescheduled = true;
            }
        } finally {
            if (!rescheduled) {
                releaseSlot(entry);
            }
        }
    }

    private void synchronizeAlarm() {
''',
)

replace_once(
    "runtime-core/src/main/java/io/github/akisarou/jvmwww/runtime/RuntimeTimerQueue.java",
    '''        entry.callback = null;
        entry.heapIndex = -1;
''',
    '''        entry.callback = null;
        entry.repeatMillis = 0L;
        entry.heapIndex = -1;
        entry.firing = false;
        entry.cancelledWhileFiring = false;
''',
)

replace_once(
    "runtime-core/src/main/java/io/github/akisarou/jvmwww/runtime/RuntimeTimerQueue.java",
    '''    private static final class TimerEntry {
        final int slotIndex;
        long generation;
        long deadlineNanos;
        long sequence;
        RuntimeTask callback;
        int heapIndex = -1;
        int nextFreeSlot = -1;
        boolean active;

        TimerEntry(int slotIndex) {
            this.slotIndex = slotIndex;
        }
    }
''',
    '''    private final class TimerEntry implements RuntimeTask {
        final int slotIndex;
        long generation;
        long deadlineNanos;
        long sequence;
        long repeatMillis;
        RuntimeTask callback;
        int heapIndex = -1;
        int nextFreeSlot = -1;
        boolean active;
        boolean firing;
        boolean cancelledWhileFiring;

        TimerEntry(int slotIndex) {
            this.slotIndex = slotIndex;
        }

        @Override
        public void execute(RuntimeInstance owner) throws Throwable {
            if (owner != runtime) {
                throw new IllegalArgumentException("Timer fired on the wrong RuntimeInstance");
            }
            boolean completed = false;
            try {
                callback.execute(owner);
                completed = true;
            } finally {
                // This runs before RuntimeInstance.leaveHostTurn() performs the callback's
                // microtask checkpoint, matching the existing ScriptC interval ordering.
                finishFiring(this, completed);
            }
        }
    }
''',
)

# ---------------------------------------------------------------------------
# RuntimeInstance public ABI
# ---------------------------------------------------------------------------

replace_once(
    "runtime-core/src/main/java/io/github/akisarou/jvmwww/runtime/RuntimeInstance.java",
    '''    /** Registers one one-shot timer and returns an exactly representable JavaScript-number handle. */
    public double setTimeout(RuntimeTask callback, double delayMilliseconds) {
        assertLanguageExecution();
        if (timerQueue == null) {
            timerQueue = new RuntimeTimerQueue(this, timerHost, hostTasksPerWake);
        }
        return timerQueue.setTimeout(callback, delayMilliseconds);
    }

    /** Cancels a timer. Invalid, stale, already-fired, and fractional handles are harmless no-ops. */
    public void clearTimeout(double handle) {
        assertLanguageExecution();
        if (timerQueue != null) {
            timerQueue.clearTimeout(handle);
        }
    }
''',
    '''    /** Registers one one-shot timer and returns an exactly representable JavaScript-number handle. */
    public double setTimeout(RuntimeTask callback, double delayMilliseconds) {
        assertLanguageExecution();
        return requireTimerQueue().setTimeout(callback, delayMilliseconds);
    }

    /** Registers an interval in the same generation-checked handle space as timeouts. */
    public double setInterval(RuntimeTask callback, double delayMilliseconds) {
        assertLanguageExecution();
        return requireTimerQueue().setInterval(callback, delayMilliseconds);
    }

    /** Cancels a timeout or interval. Invalid and stale handles are harmless no-ops. */
    public void clearTimeout(double handle) {
        assertLanguageExecution();
        if (timerQueue != null) {
            timerQueue.clearTimeout(handle);
        }
    }

    /** Cancels an interval or timeout, including a currently firing interval. */
    public void clearInterval(double handle) {
        assertLanguageExecution();
        if (timerQueue != null) {
            timerQueue.clearInterval(handle);
        }
    }

    private RuntimeTimerQueue requireTimerQueue() {
        if (timerQueue == null) {
            timerQueue = new RuntimeTimerQueue(this, timerHost, hostTasksPerWake);
        }
        return timerQueue;
    }
''',
)

# ---------------------------------------------------------------------------
# Interval conformance
# ---------------------------------------------------------------------------

interval_test = r'''package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.PromiseRejectionTracker;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free conformance tests for recursively re-armed logical intervals. */
public final class IntervalConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new IntervalConformance().run();
    }

    private void run() throws Throwable {
        testIntervalRepeatsFromPostCallbackClock();
        testSynchronousSelfClearPreventsRearm();
        testTimeoutAndIntervalShareCancellationSpace();
        testMicrotaskCanCancelTheRearmedInterval();
        testEqualDeadlineIntervalsKeepCheckpointsAndOrder();
        testCallbackFailureStopsOnlyThatInterval();
        testStaleIntervalHandleCannotCancelReusedTimeout();
        testCloseDiscardsScheduledInterval();
        testOwnerAndTurnRequirements();
        testUnsupportedHostRefusesSetInterval();
        System.out.println("Interval conformance: " + passed + " tests passed");
    }

    private void testIntervalRepeatsFromPostCallbackClock() throws Throwable {
        Fixture fixture = new Fixture(64);
        List<String> trace = new ArrayList<String>();
        double[] handle = new double[1];

        runTurn(fixture.runtime, runtime -> {
            handle[0] = runtime.setInterval(owner -> {
                trace.add("tick-" + (trace.size() + 1));
                if (trace.size() == 1) {
                    fixture.timers.advanceMillis(7);
                } else {
                    owner.clearInterval(handle[0]);
                }
            }, 5.0);
        });

        fixture.timers.advanceMillis(5);
        assertTrue(fixture.timers.runOneDue(), "first interval deadline");
        assertListEquals(Arrays.asList("tick-1"), trace, "first interval tick");
        assertEquals(17_000_000L, fixture.timers.getArmedDeadlineNanos(), "post-callback rearm");

        fixture.timers.advanceMillis(4);
        assertTrue(!fixture.timers.runOneDue(), "interval does not catch up early");
        fixture.timers.advanceMillis(1);
        assertTrue(fixture.timers.runOneDue(), "second recursive deadline");
        assertListEquals(Arrays.asList("tick-1", "tick-2"), trace, "second interval tick");
        assertTrue(!fixture.timers.isArmed(), "self-cleared interval stays stopped");
        pass();
    }

    private void testSynchronousSelfClearPreventsRearm() throws Throwable {
        Fixture fixture = new Fixture(64);
        SelfClearingTask callback = new SelfClearingTask();

        runTurn(fixture.runtime, runtime -> {
            callback.handle = runtime.setInterval(callback, 1.0);
        });
        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();

        assertEquals(1, callback.executions, "self-clearing interval execution count");
        assertEquals(0, callback.discards, "executing callback is not discarded from inside itself");
        assertTrue(!fixture.timers.isArmed(), "synchronous clearTimeout cancels interval rearm");
        pass();
    }

    private void testTimeoutAndIntervalShareCancellationSpace() throws Throwable {
        Fixture fixture = new Fixture(64);
        RecordingTask interval = new RecordingTask();
        RecordingTask timeout = new RecordingTask();

        runTurn(fixture.runtime, runtime -> {
            double intervalHandle = runtime.setInterval(interval, 10.0);
            double timeoutHandle = runtime.setTimeout(timeout, 20.0);
            runtime.clearTimeout(intervalHandle);
            runtime.clearInterval(timeoutHandle);
        });

        assertEquals(1, interval.discards, "clearTimeout discards scheduled interval");
        assertEquals(1, timeout.discards, "clearInterval discards scheduled timeout");
        assertTrue(!fixture.timers.isArmed(), "cross-cancelled timers leave no alarm");
        pass();
    }

    private void testMicrotaskCanCancelTheRearmedInterval() throws Throwable {
        Fixture fixture = new Fixture(64);
        MicrotaskClearingTask callback = new MicrotaskClearingTask();

        runTurn(fixture.runtime, runtime -> {
            callback.handle = runtime.setInterval(callback, 1.0);
        });
        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();

        assertEquals(1, callback.executions, "interval callback execution");
        assertEquals(1, callback.discards, "microtask cancellation discards the next scheduled tick");
        assertTrue(!fixture.timers.isArmed(), "microtask cancellation removes interval alarm");
        pass();
    }

    private void testEqualDeadlineIntervalsKeepCheckpointsAndOrder() throws Throwable {
        Fixture fixture = new Fixture(64);
        List<String> trace = new ArrayList<String>();
        double[] handles = new double[2];

        runTurn(fixture.runtime, runtime -> {
            handles[0] = runtime.setInterval(owner -> {
                trace.add("a");
                owner.clearInterval(handles[0]);
                owner.queueMicrotask(ignored -> trace.add("microtask-a"));
            }, 1.8);
            handles[1] = runtime.setInterval(owner -> {
                trace.add("b");
                owner.clearInterval(handles[1]);
            }, 1.1);
        });

        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();
        assertListEquals(
                Arrays.asList("a", "microtask-a", "b"),
                trace,
                "equal-deadline interval FIFO and checkpoint");
        pass();
    }

    private void testCallbackFailureStopsOnlyThatInterval() throws Throwable {
        Fixture fixture = new Fixture(64);
        IllegalStateException expected = new IllegalStateException("interval-failure");
        List<String> trace = new ArrayList<String>();

        runTurn(fixture.runtime, runtime -> {
            runtime.setInterval(owner -> {
                throw expected;
            }, 1.0);
            runtime.setTimeout(owner -> trace.add("later-timeout"), 1.0);
        });
        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();

        assertListEquals(Arrays.asList("later-timeout"), trace, "later due timeout still executes");
        assertEquals(1, fixture.errors.getEntries().size(), "interval failure report count");
        assertSame(
                RuntimeErrorPhase.HOST_TASK,
                fixture.errors.getEntries().get(0).getPhase(),
                "interval failure phase");
        assertSame(expected, fixture.errors.getEntries().get(0).getError(), "interval failure identity");
        assertTrue(!fixture.timers.isArmed(), "failed interval is not rearmed");
        pass();
    }

    private void testStaleIntervalHandleCannotCancelReusedTimeout() throws Throwable {
        Fixture fixture = new Fixture(64);
        List<String> trace = new ArrayList<String>();
        double[] handles = new double[2];

        runTurn(fixture.runtime, runtime -> {
            handles[0] = runtime.setInterval(owner -> {}, 10.0);
            runtime.clearInterval(handles[0]);
            handles[1] = runtime.setTimeout(owner -> trace.add("replacement"), 1.0);
            runtime.clearTimeout(handles[0]);
        });

        assertTrue(handles[0] != handles[1], "reused slot advances generation across timer kinds");
        fixture.timers.advanceMillis(1);
        fixture.timers.runOneDue();
        assertListEquals(Arrays.asList("replacement"), trace, "stale interval handle is harmless");
        pass();
    }

    private void testCloseDiscardsScheduledInterval() throws Throwable {
        Fixture fixture = new Fixture(64);
        RecordingTask callback = new RecordingTask();

        runTurn(fixture.runtime, runtime -> runtime.setInterval(callback, 10.0));
        Runnable staleWake = fixture.timers.getArmedCallback();
        fixture.runtime.close();

        assertEquals(1, callback.discards, "close discards scheduled interval once");
        assertTrue(!fixture.timers.isArmed(), "close disarms interval host alarm");
        staleWake.run();
        assertEquals(0, callback.executions, "stale post-close interval wake is harmless");
        pass();
    }

    private void testOwnerAndTurnRequirements() throws Throwable {
        Fixture fixture = new Fixture(64);
        Throwable idleFailure = capture(() -> fixture.runtime.setInterval(owner -> {}, 1.0));
        assertTrue(idleFailure instanceof IllegalStateException, "idle setInterval refusal");

        double[] handle = new double[1];
        runTurn(fixture.runtime, runtime -> handle[0] = runtime.setInterval(owner -> {}, 1.0));
        AtomicReference<Throwable> foreignFailure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                fixture.runtime.clearInterval(handle[0]);
            } catch (Throwable error) {
                foreignFailure.set(error);
            }
        }, "foreign-clear-interval");
        worker.start();
        worker.join();
        assertTrue(foreignFailure.get() instanceof IllegalStateException, "foreign clear refusal");
        pass();
    }

    private void testUnsupportedHostRefusesSetInterval() throws Throwable {
        ManualOwnerExecutor executor = new ManualOwnerExecutor();
        CollectingErrorReporter errors = new CollectingErrorReporter();
        RuntimeInstance runtime = new RuntimeInstance(executor, errors);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        runTurn(runtime, owner -> {
            try {
                owner.setInterval(ignored -> {}, 1.0);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        assertTrue(failure.get() instanceof UnsupportedOperationException, "unsupported interval host");
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
        final CollectingErrorReporter errors = new CollectingErrorReporter();
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

    private static final class SelfClearingTask implements RuntimeTask {
        double handle;
        int executions;
        int discards;

        @Override
        public void execute(RuntimeInstance runtime) {
            executions++;
            runtime.clearTimeout(handle);
        }

        @Override
        public void discard() {
            discards++;
        }
    }

    private static final class MicrotaskClearingTask implements RuntimeTask {
        double handle;
        int executions;
        int discards;

        @Override
        public void execute(RuntimeInstance runtime) {
            executions++;
            runtime.queueMicrotask(owner -> owner.clearInterval(handle));
        }

        @Override
        public void discard() {
            discards++;
        }
    }
}
'''

interval_test_path = ROOT / (
    "runtime-testkit/src/main/java/io/github/akisarou/jvmwww/testkit/"
    "IntervalConformance.java"
)
interval_test_path.write_text(interval_test, encoding="utf-8")

replace_once(
    "scripts/test-core.sh",
    'java -cp "$OUT" io.github.akisarou.jvmwww.testkit.TimerConformance\n',
    'java -cp "$OUT" io.github.akisarou.jvmwww.testkit.TimerConformance\n'
    'java -cp "$OUT" io.github.akisarou.jvmwww.testkit.IntervalConformance\n',
)

replace_once(
    "scripts/test-core.sh",
    '''if [[ "$timer_entry_shape" == *"java.lang.Runnable"* ]]; then
  printf 'Logical timer entries must not become one Runnable per timer\\n' >&2
  exit 1
fi
''',
    '''if [[ "$timer_entry_shape" != *"implements io.github.akisarou.jvmwww.runtime.RuntimeTask"* ]] || \\
   [[ "$timer_entry_shape" == *"java.lang.Runnable"* ]]; then
  printf 'Logical interval entries must be reusable RuntimeTask values, never one Runnable per timer\\n' >&2
  exit 1
fi
''',
)

# ---------------------------------------------------------------------------
# Repository decisions and status
# ---------------------------------------------------------------------------

adr_text = r'''# 0003 — Reuse the interval entry and re-arm before the microtask checkpoint

Status: accepted and implemented in `runtime-core`; compiler lowering and the Android `Handler` adapter remain separate integration work.

## Context

The one-shot timer substrate from decision 0002 already provides one per-runtime deadline/sequence heap, generation-checked JavaScript-number handles, one reusable `TimerHost` alarm callback, and one complete microtask checkpoint after every timer callback.

`setInterval` adds a lifetime problem that a timeout does not have. The same callback remains registered across host turns, it may cancel itself while executing, and a slow callback must not create a fixed-rate catch-up burst. The current C/LLVM runtime removes an interval from the heap while its callback runs, re-arms it from the post-callback monotonic clock with a fresh sequence, and only then performs the next microtask checkpoint. The direct-JVM runtime preserves that ordering.

## Decision

Timeouts and intervals share one logical queue and one handle space:

```java
double setTimeout(RuntimeTask callback, double delayMilliseconds);
double setInterval(RuntimeTask callback, double delayMilliseconds);
void clearTimeout(double handle);
void clearInterval(double handle);
```

Both clear functions accept either kind of timer handle. Invalid, fractional, stale, already-fired, and already-cleared handles remain no-ops.

A repeating `TimerEntry` is simultaneously its heap entry, generation-checked handle slot, and reusable `RuntimeTask` for every tick. The callback is not wrapped in a newly allocated job or platform `Runnable` on each repetition.

One-shot timeouts retain their established behavior: the timeout slot is released before its callback executes. Adding intervals does not change timeout handle reuse.

## Firing lifecycle

A due interval follows:

```text
scheduled in heap
    -> removed and marked firing
    -> callback body executes as one host task
    -> if successful and not cleared:
           deadline = post-callback monotonic now + period
           sequence = fresh registration sequence
           same TimerEntry returns to heap
    -> callback host turn exits
    -> microtasks and rejection observation run
```

The next deadline is recursive fixed-delay scheduling, not fixed-rate scheduling. Time spent in the callback moves the deadline forward; missed periods do not create catch-up invocations. A fresh sequence also means a timer registered by the callback at the same deadline precedes the interval's next tick.

## Re-arm before microtasks

The entry re-enters the heap in `TimerEntry.execute`'s completion path, before control returns to `RuntimeInstance.executeHostTask`. `leaveHostTurn` then performs the microtask checkpoint.

This matches the C/LLVM runtime. A microtask created by the callback therefore sees the next tick as an ordinary scheduled timer and can cancel it eagerly; that cancellation invokes `RuntimeTask.discard()` exactly once for the future delivery.

Re-arming after the checkpoint is rejected because it changes the next deadline by the duration of callback-created microtasks and gives those microtasks a special still-firing cancellation state.

## Self-cancellation

The interval stays generation-valid while its callback body runs. Calling either clear function with its handle sets `cancelledWhileFiring` on that exact entry. After the callback returns, the flag prevents re-arm and the slot is released. `discard()` is not called for the currently executing callback because that delivery already occurred.

A microtask cancellation is different: the entry is already re-armed, so cancellation removes a future delivery and calls `discard()`.

## Failure behavior

If an interval callback throws a non-fatal failure, the entry releases its slot without re-arming, the failure reaches the existing host-task error reporter, and later due timers may still execute under the fairness budget. Fatal VM, linkage, and thread-death failures retain the runtime's existing rules.

## Allocation policy

The first registration of a concurrent slot allocates one `TimerEntry`. Later ticks reuse the same object. Per tick the timer core allocates zero timer entries, zero wrapper runtime jobs, and zero platform runnables.

## Rejected alternatives

- Platform repeating timers: they would own language timing and thread selection.
- Fixed-rate catch-up: it diverges from the ScriptC/libuv-style recursive interval contract.
- A new timer node per tick: the existing slot already has the correct lifetime.
- Releasing the slot before the callback: synchronous self-clear could not identify the firing interval.
- Re-arming after microtasks: it changes timing and cancellation state.
- Calling `discard()` during synchronous self-clear: the current delivery is already executing.

## Consequences

- `setInterval` and `clearInterval` reuse the one-alarm timer architecture.
- A repeating tick creates no timer wrapper allocation.
- Intervals are self-cancellable and stale-handle safe.
- Microtasks run between callbacks and may cancel the already-rearmed next tick.
- Slow callbacks do not produce catch-up bursts.
- A thrown callback stops that interval without stranding unrelated timers.

## Required evidence

Permanent tests and structural checks prove:

- post-callback `now + period` scheduling and no catch-up;
- synchronous self-clear without discarding the running callback;
- microtask cancellation of the already-rearmed tick;
- cross-clearing between timeout and interval handles;
- equal-deadline ordering with separate checkpoints;
- callback failure, stale generations, shutdown, and owner confinement;
- `TimerEntry implements RuntimeTask` and does not implement `Runnable`;
- no future, coroutine, or platform repeating scheduler enters `runtime-core`.
'''
ADR.write_text(adr_text, encoding="utf-8")

replace_once(
    "README.md",
    "compiler-facing async-frame ABI, and one-shot logical timers with:",
    "compiler-facing async-frame ABI, and logical timeouts and intervals with:",
)
replace_once(
    "README.md",
    "- a per-runtime deadline/sequence heap for `setTimeout` and `clearTimeout`;\n",
    "- a per-runtime deadline/sequence heap shared by timeouts and intervals;\n",
)
replace_once(
    "README.md",
    "- one reusable platform timer alarm with a full microtask checkpoint between timer callbacks;\n",
    "- one reusable platform timer alarm with a full microtask checkpoint between timer callbacks;\n"
    "- allocation-free interval ticks that recursively re-arm from the post-callback clock;\n"
    "- synchronous self-cancellation and microtask cancellation of the already-rearmed next tick;\n",
)
replace_once(
    "README.md",
    "Promise combinators, intervals, the Android `Handler` adapter,",
    "Promise combinators, the Android `Handler` adapter,",
)
replace_once(
    "README.md",
    "[decision 0002](docs/decisions/0002-one-armed-logical-timers.md), and the",
    "[decision 0002](docs/decisions/0002-one-armed-logical-timers.md), "
    "[decision 0003](docs/decisions/0003-reuse-interval-entry.md), and the",
)

replace_once(
    "AGENTS.md",
    "- Logical timers use one per-runtime deadline heap and at most one armed platform callback. Do not add `ScheduledExecutorService`, one `Runnable` per timer, or a periodic timer pump.\n",
    "- Logical timers use one per-runtime deadline heap and at most one armed platform callback. Do not add `ScheduledExecutorService`, one `Runnable` per timer, or a periodic timer pump.\n"
    "- Intervals recursively re-arm from the post-callback monotonic clock before the callback's microtask checkpoint. Preserve self-clear, microtask cancellation, and no-catch-up behavior.\n",
)

replace_once(
    "docs/architecture.md",
    "## Implemented one-shot timer ABI",
    "## Implemented timer ABI",
)
replace_once(
    "docs/architecture.md",
    "`RuntimeInstance.setTimeout` and `clearTimeout` use a lazily allocated deadline/sequence min-heap.",
    "`RuntimeInstance.setTimeout`, `clearTimeout`, `setInterval`, and `clearInterval` use a lazily allocated deadline/sequence min-heap.",
)
replace_once(
    "docs/architecture.md",
    '''Every due callback runs through the ordinary host-task entry path and therefore receives a full microtask and rejection checkpoint before another due callback. Cancellation and shutdown eagerly call `RuntimeTask.discard()` so retained native or transport resources can be released.

The full contract, current delay profile, handle encoding, and Android adapter requirements are normative in [decision 0002](decisions/0002-one-armed-logical-timers.md).
''',
    '''Every due callback runs through the ordinary host-task entry path and therefore receives a full microtask and rejection checkpoint before another due callback. Cancellation and shutdown eagerly call `RuntimeTask.discard()` so retained native or transport resources can be released.

An interval reuses its `TimerEntry` as the host-task body. After a successful callback it re-arms from the post-callback monotonic clock with a fresh sequence, before the microtask checkpoint. This prevents fixed-rate catch-up, lets synchronous self-clear suppress the re-arm, and lets a callback-created microtask cancel the already-scheduled next tick.

The shared timer substrate, delay profile, handle encoding, and Android adapter requirements are normative in [decision 0002](decisions/0002-one-armed-logical-timers.md). Interval lifetime, re-arm timing, and allocation rules are normative in [decision 0003](decisions/0003-reuse-interval-entry.md).
''',
)
replace_once(
    "docs/architecture.md",
    "- [0002 — Keep logical timers in each runtime and arm one host deadline](decisions/0002-one-armed-logical-timers.md)\n",
    "- [0002 — Keep logical timers in each runtime and arm one host deadline](decisions/0002-one-armed-logical-timers.md)\n"
    "- [0003 — Reuse the interval entry and re-arm before the microtask checkpoint](decisions/0003-reuse-interval-entry.md)\n",
)

replace_once(
    "docs/decisions/0002-one-armed-logical-timers.md",
    "Status: accepted and implemented for one-shot `setTimeout` / `clearTimeout`; intervals and the Android `Handler` adapter remain later slices.",
    "Status: accepted and implemented for the shared timeout/interval substrate. Interval repetition is specified by decision 0003; the Android `Handler` adapter remains a later slice.",
)
replace_once(
    "docs/decisions/0002-one-armed-logical-timers.md",
    "- Intervals, refresh/ref/unref, trailing-argument lowering, and Android packaging remain explicit later work.",
    "- Interval repetition is implemented under decision 0003. Refresh/ref/unref, trailing-argument lowering, and Android packaging remain explicit later work.",
)
with (ROOT / "docs/decisions/0002-one-armed-logical-timers.md").open(
    "a", encoding="utf-8"
) as stream:
    stream.write(
        "\n## Follow-on decision\n\n"
        "- [0003 — Reuse the interval entry and re-arm before the microtask checkpoint]"
        "(0003-reuse-interval-entry.md)\n"
    )

print("Applied interval runtime, tests, and decision record")
