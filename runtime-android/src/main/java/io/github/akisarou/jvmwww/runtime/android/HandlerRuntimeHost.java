package io.github.akisarou.jvmwww.runtime.android;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.akisarou.jvmwww.runtime.OwnerExecutor;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.TimerHost;
import java.util.Objects;

/**
 * Binds one {@link RuntimeInstance} to one Android {@link Looper}.
 *
 * <p>The same object implements the runtime owner executor and its one replaceable timer alarm.
 * Logical Promise and timer behavior remains in {@code runtime-core}; this adapter owns only
 * Android queueing, owner identity, and the uptime clock used by {@link Handler#postAtTime}.</p>
 */
public final class HandlerRuntimeHost implements OwnerExecutor, TimerHost {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Handler handler;
    private final Looper looper;
    private Runnable armedTimerCallback;

    /** Creates a new Handler attached to {@code looper}. */
    public HandlerRuntimeHost(Looper looper) {
        this(new Handler(Objects.requireNonNull(looper, "looper")));
    }

    /** Uses an existing Handler and therefore its exact Looper queue. */
    public HandlerRuntimeHost(Handler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.looper = Objects.requireNonNull(handler.getLooper(), "handler.looper");
    }

    /** Creates a host for the current thread's Looper or fails explicitly when none exists. */
    public static HandlerRuntimeHost forCurrentLooper() {
        Looper looper = Looper.myLooper();
        if (looper == null) {
            throw new IllegalStateException("Current thread has no Android Looper");
        }
        return new HandlerRuntimeHost(looper);
    }

    public Looper getLooper() {
        return looper;
    }

    @Override
    public boolean isOwnerThread() {
        return Looper.myLooper() == looper;
    }

    /** May be called from any thread. Handler preserves asynchronous FIFO delivery. */
    @Override
    public void post(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (!handler.post(callback)) {
            throw new IllegalStateException("Android Looper rejected runtime owner wake");
        }
    }

    /**
     * Returns the Handler uptime time base in nanoseconds.
     *
     * <p>Using uptime rather than {@link System#nanoTime()} is required because Android absolute
     * Handler deadlines are expressed in {@link SystemClock#uptimeMillis()}.</p>
     */
    @Override
    public long nowNanos() {
        assertOwnerThread();
        long uptimeMillis = SystemClock.uptimeMillis();
        if (uptimeMillis < 0L) {
            throw new IllegalStateException("Android uptime moved below zero");
        }
        if (uptimeMillis > Long.MAX_VALUE / NANOS_PER_MILLI) {
            return Long.MAX_VALUE;
        }
        return uptimeMillis * NANOS_PER_MILLI;
    }

    /** Replaces the one platform alarm and never invokes {@code wakeCallback} inline. */
    @Override
    public void arm(long deadlineNanos, Runnable wakeCallback) {
        assertOwnerThread();
        if (deadlineNanos < 0L) {
            throw new IllegalArgumentException("deadlineNanos must be non-negative");
        }
        Objects.requireNonNull(wakeCallback, "wakeCallback");

        Runnable previous = armedTimerCallback;
        armedTimerCallback = null;
        if (previous != null) {
            handler.removeCallbacks(previous);
        }

        long deadlineMillis = ceilNanosToMillis(deadlineNanos);
        if (!handler.postAtTime(wakeCallback, deadlineMillis)) {
            throw new IllegalStateException("Android Looper rejected runtime timer alarm");
        }
        armedTimerCallback = wakeCallback;
    }

    @Override
    public void disarm() {
        assertOwnerThread();
        Runnable callback = armedTimerCallback;
        armedTimerCallback = null;
        if (callback != null) {
            handler.removeCallbacks(callback);
        }
    }

    static long ceilNanosToMillis(long nanoseconds) {
        long milliseconds = nanoseconds / NANOS_PER_MILLI;
        if (nanoseconds % NANOS_PER_MILLI != 0L) {
            milliseconds++;
        }
        return milliseconds;
    }

    private void assertOwnerThread() {
        if (!isOwnerThread()) {
            throw new IllegalStateException(
                    "Android runtime host accessed outside its Looper thread");
        }
    }
}
