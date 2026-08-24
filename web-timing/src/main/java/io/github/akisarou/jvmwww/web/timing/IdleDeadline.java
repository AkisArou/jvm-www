package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Objects;

/** One observable deadline value supplied to an idle callback. */
public final class IdleDeadline {
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final RuntimeInstance runtime;
    private final MonotonicClock clock;
    private final long deadlineNanos;
    private final boolean didTimeout;

    IdleDeadline(
            RuntimeInstance runtime,
            MonotonicClock clock,
            long deadlineNanos,
            boolean didTimeout) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlineNanos = deadlineNanos;
        this.didTimeout = didTimeout;
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public boolean isDidTimeout() {
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        return didTimeout;
    }

    /** Returns non-negative milliseconds remaining in this deadline's monotonic time domain. */
    public double timeRemaining() {
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        long now = clock.nowNanos();
        if (now >= deadlineNanos) {
            return 0.0;
        }
        long remaining = deadlineNanos - now;
        if (remaining < 0L) {
            // Signed subtraction overflow means the positive mathematical difference is larger
            // than Long.MAX_VALUE. The selected host profile caps ordinary deadlines to 50ms, but
            // keep this object total for arbitrary monotonic origins.
            return Long.MAX_VALUE / NANOS_PER_MILLISECOND;
        }
        return remaining / NANOS_PER_MILLISECOND;
    }
}
