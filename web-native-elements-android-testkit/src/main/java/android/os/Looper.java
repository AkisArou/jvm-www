package android.os;

/** Minimal deterministic owner identity used only by web-native-elements-android-testkit. */
public final class Looper {
    private static final ThreadLocal<Looper> CURRENT = new ThreadLocal<Looper>();

    private final Thread ownerThread = Thread.currentThread();

    private Looper() {}

    public static void prepare() {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Only one Looper may be created per thread");
        }
        CURRENT.set(new Looper());
    }

    public static Looper myLooper() {
        return CURRENT.get();
    }

    public Thread getThread() {
        return ownerThread;
    }
}
