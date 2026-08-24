package io.github.akisarou.jvmwww.web.timing;

/** Owner-thread monotonic clock used by Web timing capabilities. */
public interface MonotonicClock {
    /**
     * Returns a timestamp from one arbitrary but stable nanosecond time base.
     *
     * <p>The value may be negative because only differences are observable. Successive owner-thread
     * reads must not move backwards during one runtime lifetime.</p>
     */
    long nowNanos();
}
