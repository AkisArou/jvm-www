package android.os;

/** Minimal deterministic uptime clock used only by runtime-android-testkit. */
public final class SystemClock {
    private static long uptimeMillis;

    private SystemClock() {}

    public static long uptimeMillis() {
        return uptimeMillis;
    }

    public static void setUptimeMillisForTest(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("uptime must be non-negative");
        }
        uptimeMillis = value;
    }

    public static void advanceMillisForTest(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("advance must be non-negative");
        }
        uptimeMillis += value;
    }
}
