package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.TimerHost;

/** Deterministic owner-thread timer host for runtime conformance tests. */
public final class ManualTimerHost implements TimerHost {
    private long nowNanos;
    private long armedDeadlineNanos;
    private Runnable armedCallback;
    private int armCount;
    private int disarmCount;

    @Override
    public long nowNanos() {
        return nowNanos;
    }

    @Override
    public void arm(long deadlineNanos, Runnable wakeCallback) {
        if (deadlineNanos < 0L) {
            throw new IllegalArgumentException("deadlineNanos must be non-negative");
        }
        armedDeadlineNanos = deadlineNanos;
        armedCallback = wakeCallback;
        armCount++;
    }

    @Override
    public void disarm() {
        if (armedCallback != null) {
            armedCallback = null;
            disarmCount++;
        }
    }

    public void advanceMillis(long milliseconds) {
        if (milliseconds < 0L) {
            throw new IllegalArgumentException("milliseconds must be non-negative");
        }
        nowNanos += milliseconds * 1_000_000L;
    }

    /** Runs at most one armed callback whose deadline is now due. */
    public boolean runOneDue() {
        if (armedCallback == null || armedDeadlineNanos > nowNanos) {
            return false;
        }
        Runnable callback = armedCallback;
        armedCallback = null;
        callback.run();
        return true;
    }

    public boolean isArmed() {
        return armedCallback != null;
    }

    public long getArmedDeadlineNanos() {
        if (armedCallback == null) {
            throw new IllegalStateException("no timer alarm is armed");
        }
        return armedDeadlineNanos;
    }

    public int getArmCount() {
        return armCount;
    }

    public int getDisarmCount() {
        return disarmCount;
    }

    public Runnable getArmedCallback() {
        return armedCallback;
    }
}
