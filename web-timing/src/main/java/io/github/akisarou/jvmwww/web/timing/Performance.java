package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Objects;

/** Owner-confined high-resolution monotonic clock with one per-object time origin. */
public final class Performance {
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final RuntimeInstance runtime;
    private final MonotonicClock clock;
    private final long originNanos;
    private long lastClockNanos;
    private double lastNowMilliseconds;

    public Performance(RuntimeInstance runtime, MonotonicClock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        this.clock = Objects.requireNonNull(clock, "clock");
        originNanos = clock.nowNanos();
        lastClockNanos = originNanos;
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    /** Returns a non-decreasing timestamp in milliseconds relative to this Performance origin. */
    public double now() {
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        long current = clock.nowNanos();
        if (current < lastClockNanos) {
            throw new IllegalStateException("MonotonicClock timestamp moved backwards");
        }
        lastClockNanos = current;
        double milliseconds = elapsedMilliseconds(originNanos, current);
        if (milliseconds < lastNowMilliseconds) {
            // Preserve monotonicity across the final double conversion on very large time bases.
            milliseconds = lastNowMilliseconds;
        } else {
            lastNowMilliseconds = milliseconds;
        }
        return milliseconds;
    }

    /** Converts a host frame timestamp without taking another clock sample. */
    double frameTimestampMilliseconds(long frameTimeNanos) {
        double milliseconds = elapsedMilliseconds(originNanos, frameTimeNanos);
        return milliseconds < 0.0 ? 0.0 : milliseconds;
    }

    private static double elapsedMilliseconds(long origin, long current) {
        if (current >= origin) {
            long elapsed = current - origin;
            if (elapsed >= 0L) {
                return elapsed / NANOS_PER_MILLISECOND;
            }
            // A runtime older than the signed-nanosecond range is outside the selected profile.
            return Long.MAX_VALUE / NANOS_PER_MILLISECOND;
        }
        return -elapsedMilliseconds(current, origin);
    }
}
