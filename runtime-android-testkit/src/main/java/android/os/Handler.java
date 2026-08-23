package android.os;

import java.util.Objects;

/** Minimal deterministic Handler used only by runtime-android-testkit. */
public class Handler {
    private final Looper looper;

    public Handler(Looper looper) {
        this.looper = Objects.requireNonNull(looper, "looper");
    }

    public Looper getLooper() {
        return looper;
    }

    public boolean post(Runnable callback) {
        return looper.enqueue(
                Objects.requireNonNull(callback, "callback"),
                SystemClock.uptimeMillis());
    }

    public boolean postAtTime(Runnable callback, long uptimeMillis) {
        return looper.enqueue(Objects.requireNonNull(callback, "callback"), uptimeMillis);
    }

    public void removeCallbacks(Runnable callback) {
        looper.remove(Objects.requireNonNull(callback, "callback"));
    }
}
