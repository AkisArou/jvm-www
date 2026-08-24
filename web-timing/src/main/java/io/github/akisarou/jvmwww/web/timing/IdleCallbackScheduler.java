package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.Objects;

/** Allocation-bounded requestIdleCallback scheduling over one replaceable idle-period host. */
public final class IdleCallbackScheduler
        implements IdleCallbackHost.IdleCallback, RuntimeTask, RuntimeOwnedResource {
    private static final double MAX_TIMER_DELAY_MILLISECONDS = 2_147_483_647.0;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final long MAX_IDLE_PERIOD_NANOS = 50L * NANOS_PER_MILLISECOND;

    private final RuntimeInstance runtime;
    private final IdleCallbackHost host;
    private final IdleCallbackExceptionReporter exceptionReporter;
    private final IdleCallbackStore store = new IdleCallbackStore();

    private boolean idleRequested;
    private boolean insideIdleDelivery;
    private boolean timeoutContinuationQueued;
    private boolean closed;
    private double timeoutTimerHandle;
    private long armedTimeoutDeadlineNanos = IdleCallbackStore.NO_TIMEOUT;
    private int runtimeResourceSlot = IdleCallbackStore.NO_SLOT;

    public IdleCallbackScheduler(RuntimeInstance runtime, IdleCallbackHost host) {
        this(runtime, host, DefaultIdleCallbackExceptionReporter.INSTANCE);
    }

    public IdleCallbackScheduler(
            RuntimeInstance runtime,
            IdleCallbackHost host,
            IdleCallbackExceptionReporter exceptionReporter) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        this.host = Objects.requireNonNull(host, "host");
        this.exceptionReporter = Objects.requireNonNull(exceptionReporter, "exceptionReporter");
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public double requestIdleCallback(IdleRequestCallback callback) {
        return requestIdleCallback(callback, 0.0);
    }

    /** Registers one callback; zero means no timeout race and fractional timeouts are truncated. */
    public double requestIdleCallback(IdleRequestCallback callback, double timeoutMilliseconds) {
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        ensureOpen();
        IdleRequestCallback checked = Objects.requireNonNull(callback, "callback");
        long timeout = coerceTimeout(timeoutMilliseconds);
        long timeoutDeadline = timeout == 0L
                ? IdleCallbackStore.NO_TIMEOUT
                : saturatingAdd(host.nowNanos(), timeout * NANOS_PER_MILLISECOND);
        int slot = store.allocate(checked, timeoutDeadline);

        Throwable failure = null;
        try {
            ensureRuntimeOwnership();
            ensureIdleRequested();
            updateTimeoutAlarm();
        } catch (Throwable error) {
            rethrowIfFatal(error);
            failure = error;
        }
        if (failure != null) {
            rollbackRegistration(slot, failure);
            throwAsUnchecked(failure);
        }
        return store.handle(slot);
    }

    /** Invalid, fractional, stale, and already-run handles are no-ops. */
    public void cancelIdleCallback(double handle) {
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        if (closed) {
            return;
        }
        int slot = store.lookupHandle(handle);
        if (slot == IdleCallbackStore.NO_SLOT) {
            return;
        }
        store.remove(slot);

        Throwable failure = null;
        try {
            updateTimeoutAlarm();
            if (store.activeCount() == 0) {
                cancelEmptyIdleRequest();
            }
        } catch (Throwable error) {
            rethrowIfFatal(error);
            failure = error;
        } finally {
            releaseRuntimeOwnershipIfIdle();
        }
        if (failure != null) {
            throwAsUnchecked(failure);
        }
    }

    /** One host idle notification invokes at most one callback and one final checkpoint. */
    @Override
    public void onIdle(long deadlineNanos) {
        if (runtime.isClosed() || closed || !idleRequested) {
            return;
        }
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException("Idle period delivered outside the runtime owner");
        }

        idleRequested = false;
        runtime.enterHostTurn();
        insideIdleDelivery = true;
        try {
            store.movePendingToRunnable();
            int slot = store.firstRunnable();
            if (slot == IdleCallbackStore.NO_SLOT) {
                return;
            }

            long now = host.nowNanos();
            long effectiveDeadline = clampIdleDeadline(now, deadlineNanos);
            if (effectiveDeadline <= now) {
                return;
            }

            IdleRequestCallback callback = store.detach(slot);
            IdleDeadline deadline = new IdleDeadline(runtime, host, effectiveDeadline, false);
            invokeCallback(callback, deadline);
        } finally {
            insideIdleDelivery = false;
            try {
                scheduleAfterDelivery();
            } finally {
                runtime.leaveHostTurn();
            }
        }
    }

    /** Logical timeout alarm and due-timeout continuation path. */
    @Override
    public void execute(RuntimeInstance owner) {
        if (owner != runtime) {
            throw new IllegalArgumentException("Idle timeout delivered by another runtime");
        }
        if (closed || runtime.isClosed()) {
            return;
        }

        timeoutTimerHandle = 0.0;
        armedTimeoutDeadlineNanos = IdleCallbackStore.NO_TIMEOUT;
        timeoutContinuationQueued = false;

        long now = host.nowNanos();
        int slot = store.firstTimeout();
        if (slot == IdleCallbackStore.NO_SLOT || store.timeoutDeadline(slot) > now) {
            scheduleAfterDelivery();
            return;
        }

        IdleRequestCallback callback = store.detach(slot);
        IdleDeadline deadline = new IdleDeadline(runtime, host, now, true);
        try {
            invokeCallback(callback, deadline);
        } finally {
            scheduleAfterDelivery();
        }
    }

    /** Runtime shutdown cancels the exact idle request, one timer, and retained callbacks. */
    @Override
    public void closeForRuntime() {
        if (closed) {
            return;
        }
        closed = true;
        runtimeResourceSlot = IdleCallbackStore.NO_SLOT;

        Throwable failure = null;
        if (idleRequested) {
            try {
                host.cancelIdle(this);
                idleRequested = false;
            } catch (Throwable error) {
                rethrowIfFatal(error);
                failure = error;
            }
        }
        if (timeoutTimerHandle != 0.0) {
            try {
                runtime.clearTimeout(timeoutTimerHandle);
                timeoutTimerHandle = 0.0;
                armedTimeoutDeadlineNanos = IdleCallbackStore.NO_TIMEOUT;
            } catch (Throwable error) {
                rethrowIfFatal(error);
                failure = rememberFailure(failure, error);
            }
        }

        timeoutContinuationQueued = false;
        insideIdleDelivery = false;
        store.clear();
        if (failure != null) {
            throwAsUnchecked(failure);
        }
    }

    private void invokeCallback(IdleRequestCallback callback, IdleDeadline deadline) {
        try {
            callback.run(deadline);
        } catch (Throwable error) {
            rethrowIfFatal(error);
            try {
                exceptionReporter.report(runtime, error);
            } catch (Throwable reporterFailure) {
                rethrowIfFatal(reporterFailure);
                if (reporterFailure != error) {
                    error.addSuppressed(reporterFailure);
                }
                DefaultIdleCallbackExceptionReporter.INSTANCE.report(runtime, error);
            }
        }
    }

    private void scheduleAfterDelivery() {
        if (closed || runtime.isClosed()) {
            return;
        }
        Throwable failure = null;
        try {
            if (store.activeCount() > 0) {
                ensureIdleRequested();
            } else {
                cancelEmptyIdleRequest();
            }
            updateTimeoutAlarm();
        } catch (Throwable error) {
            rethrowIfFatal(error);
            failure = error;
        } finally {
            releaseRuntimeOwnershipIfIdle();
        }
        if (failure != null) {
            throwAsUnchecked(failure);
        }
    }

    private void ensureIdleRequested() {
        if (idleRequested || insideIdleDelivery || store.activeCount() == 0) {
            return;
        }
        idleRequested = true;
        try {
            host.requestIdle(this);
        } catch (RuntimeException error) {
            idleRequested = false;
            throw error;
        } catch (Error error) {
            idleRequested = false;
            throw error;
        }
    }

    private void cancelEmptyIdleRequest() {
        if (!idleRequested || store.activeCount() != 0) {
            return;
        }
        host.cancelIdle(this);
        idleRequested = false;
    }

    private void updateTimeoutAlarm() {
        if (timeoutContinuationQueued) {
            return;
        }
        long earliest = store.earliestTimeoutDeadline();
        if (earliest == IdleCallbackStore.NO_TIMEOUT) {
            clearTimeoutAlarm();
            return;
        }

        long now = host.nowNanos();
        if (earliest <= now) {
            clearTimeoutAlarm();
            queueTimeoutContinuation();
            return;
        }
        if (timeoutTimerHandle != 0.0 && armedTimeoutDeadlineNanos == earliest) {
            return;
        }

        clearTimeoutAlarm();
        long delayMilliseconds = divideRoundUp(
                positiveDifference(earliest, now), NANOS_PER_MILLISECOND);
        if (delayMilliseconds <= 0L) {
            delayMilliseconds = 1L;
        }
        timeoutTimerHandle = runtime.setTimeout(this, (double) delayMilliseconds);
        armedTimeoutDeadlineNanos = earliest;
    }

    private void clearTimeoutAlarm() {
        if (timeoutTimerHandle == 0.0) {
            armedTimeoutDeadlineNanos = IdleCallbackStore.NO_TIMEOUT;
            return;
        }
        runtime.clearTimeout(timeoutTimerHandle);
        timeoutTimerHandle = 0.0;
        armedTimeoutDeadlineNanos = IdleCallbackStore.NO_TIMEOUT;
    }

    private void queueTimeoutContinuation() {
        if (timeoutContinuationQueued) {
            return;
        }
        timeoutContinuationQueued = true;
        boolean admitted;
        try {
            admitted = runtime.admitHostTask(this);
        } catch (RuntimeException error) {
            timeoutContinuationQueued = false;
            throw error;
        } catch (Error error) {
            timeoutContinuationQueued = false;
            throw error;
        }
        if (!admitted) {
            timeoutContinuationQueued = false;
        }
    }

    private void ensureRuntimeOwnership() {
        if (runtimeResourceSlot == IdleCallbackStore.NO_SLOT) {
            runtimeResourceSlot = runtime.registerOwnedResource(this);
        }
    }

    private void releaseRuntimeOwnershipIfIdle() {
        if (runtimeResourceSlot == IdleCallbackStore.NO_SLOT
                || store.activeCount() != 0
                || idleRequested
                || insideIdleDelivery
                || timeoutTimerHandle != 0.0
                || timeoutContinuationQueued) {
            return;
        }
        int slot = runtimeResourceSlot;
        runtimeResourceSlot = IdleCallbackStore.NO_SLOT;
        runtime.unregisterOwnedResource(this, slot);
    }

    private void rollbackRegistration(int slot, Throwable failure) {
        if (store.isActive(slot)) {
            store.remove(slot);
        }
        try {
            updateTimeoutAlarm();
        } catch (Throwable cleanupFailure) {
            rethrowIfFatal(cleanupFailure);
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (store.activeCount() == 0) {
            try {
                cancelEmptyIdleRequest();
            } catch (Throwable cleanupFailure) {
                rethrowIfFatal(cleanupFailure);
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        releaseRuntimeOwnershipIfIdle();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("IdleCallbackScheduler is closed");
        }
    }

    private static long coerceTimeout(double milliseconds) {
        if (Double.isNaN(milliseconds)
                || Double.isInfinite(milliseconds)
                || milliseconds < 0.0
                || milliseconds > MAX_TIMER_DELAY_MILLISECONDS) {
            throw new IllegalArgumentException(
                    "Idle callback timeout must be finite and between 0 and 2^31-1 ms");
        }
        return (long) milliseconds;
    }

    private static long clampIdleDeadline(long now, long requestedDeadline) {
        if (requestedDeadline <= now) {
            return now;
        }
        long maximum = saturatingAdd(now, MAX_IDLE_PERIOD_NANOS);
        return requestedDeadline < maximum ? requestedDeadline : maximum;
    }

    private static long saturatingAdd(long value, long positiveAmount) {
        if (value > Long.MAX_VALUE - positiveAmount) {
            return Long.MAX_VALUE;
        }
        return value + positiveAmount;
    }

    private static long positiveDifference(long later, long earlier) {
        if (later <= earlier) {
            return 0L;
        }
        long difference = later - earlier;
        return difference < 0L ? Long.MAX_VALUE : difference;
    }

    private static long divideRoundUp(long value, long divisor) {
        long quotient = value / divisor;
        return value % divisor == 0L ? quotient : quotient + 1L;
    }

    private static Throwable rememberFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void rethrowIfFatal(Throwable error) {
        if (error instanceof ThreadDeath) {
            throw (ThreadDeath) error;
        }
        if (error instanceof VirtualMachineError) {
            throw (VirtualMachineError) error;
        }
        if (error instanceof LinkageError) {
            throw (LinkageError) error;
        }
    }

    private static void throwAsUnchecked(Throwable error) {
        if (error instanceof RuntimeException) {
            throw (RuntimeException) error;
        }
        if (error instanceof Error) {
            throw (Error) error;
        }
        throw new IllegalStateException(error);
    }
}
