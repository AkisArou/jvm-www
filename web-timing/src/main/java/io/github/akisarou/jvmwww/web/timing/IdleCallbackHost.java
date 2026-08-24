package io.github.akisarou.jvmwww.web.timing;

/** Platform capability for one replaceable idle-period notification. */
public interface IdleCallbackHost extends MonotonicClock {
    /** Reusable callback supplied by one {@link IdleCallbackScheduler}. */
    interface IdleCallback {
        /**
         * Runs asynchronously on the runtime owner thread.
         *
         * @param deadlineNanos estimated end of this idle period in the host clock's time base
         */
        void onIdle(long deadlineNanos);
    }

    /** Requests one future idle-period notification for {@code callback}. */
    void requestIdle(IdleCallback callback);

    /** Cancels the exact pending idle request for {@code callback}, if present. */
    void cancelIdle(IdleCallback callback);
}
