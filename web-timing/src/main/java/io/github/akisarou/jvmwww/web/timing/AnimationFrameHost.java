package io.github.akisarou.jvmwww.web.timing;

/** Platform capability for one replaceable display-frame callback. */
public interface AnimationFrameHost extends MonotonicClock {
    /** Reusable callback supplied by one {@link AnimationFrameScheduler}. */
    interface FrameCallback {
        /** Runs asynchronously on the runtime owner thread. */
        void onFrame(long frameTimeNanos);
    }

    /**
     * Requests the next display frame for {@code callback}.
     *
     * <p>The callback must run asynchronously on the owner thread and at most once for this request.
     * The timestamp must use the same monotonic time base as {@link #nowNanos()}.</p>
     */
    void requestFrame(FrameCallback callback);

    /** Cancels the exact pending request for {@code callback}, if present. */
    void cancelFrame(FrameCallback callback);
}
