package io.github.akisarou.jvmwww.runtime;

/**
 * Platform adapter for one per-runtime absolute monotonic timer alarm.
 *
 * <p>All methods are called on the {@link RuntimeInstance} owner thread. Calling {@link #arm}
 * replaces any previous alarm. The host must invoke the supplied reusable callback asynchronously
 * on that same owner thread, never inline from {@code arm}. A runtime with no timer capability uses
 * {@link #UNSUPPORTED}.</p>
 */
public interface TimerHost {
    TimerHost UNSUPPORTED = new TimerHost() {
        @Override
        public long nowNanos() {
            throw unsupported();
        }

        @Override
        public void arm(long deadlineNanos, Runnable wakeCallback) {
            throw unsupported();
        }

        @Override
        public void disarm() {}

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("RuntimeInstance has no timer host");
        }
    };

    /** Returns a non-negative monotonic timestamp in nanoseconds. */
    long nowNanos();

    /** Replaces the current alarm with one for {@code deadlineNanos}. */
    void arm(long deadlineNanos, Runnable wakeCallback);

    /** Cancels the current alarm, if one is armed. */
    void disarm();
}
