package io.github.akisarou.jvmwww.web.timing.android;

import android.os.Looper;
import android.view.Choreographer;
import io.github.akisarou.jvmwww.web.timing.AnimationFrameHost;
import java.util.Objects;

/**
 * Binds one {@link AnimationFrameHost} to the {@link Choreographer} of one Android Looper.
 *
 * <p>The host object itself is the reusable Android frame callback. It retains at most one
 * scheduler callback and creates no wrapper or Runnable for an individual frame request.</p>
 */
public final class ChoreographerAnimationFrameHost
        implements AnimationFrameHost, Choreographer.FrameCallback {
    private final Looper looper;
    private final Choreographer choreographer;
    private FrameCallback pendingCallback;

    private ChoreographerAnimationFrameHost(Looper looper, Choreographer choreographer) {
        this.looper = Objects.requireNonNull(looper, "looper");
        this.choreographer = Objects.requireNonNull(choreographer, "choreographer");
    }

    /** Creates a frame host attached to the calling thread's Looper. */
    public static ChoreographerAnimationFrameHost forCurrentLooper() {
        Looper looper = Looper.myLooper();
        if (looper == null) {
            throw new IllegalStateException("Current thread has no Android Looper");
        }
        return new ChoreographerAnimationFrameHost(looper, Choreographer.getInstance());
    }

    public Looper getLooper() {
        return looper;
    }

    /** Uses the same monotonic nanosecond domain as Choreographer frame timestamps. */
    @Override
    public long nowNanos() {
        assertOwnerThread();
        return System.nanoTime();
    }

    /** Posts this reusable Android callback once for the supplied scheduler callback. */
    @Override
    public void requestFrame(FrameCallback callback) {
        assertOwnerThread();
        FrameCallback checked = Objects.requireNonNull(callback, "callback");
        if (pendingCallback != null) {
            throw new IllegalStateException("An animation frame is already pending");
        }

        pendingCallback = checked;
        try {
            choreographer.postFrameCallback(this);
        } catch (RuntimeException error) {
            pendingCallback = null;
            throw error;
        } catch (Error error) {
            pendingCallback = null;
            throw error;
        }
    }

    /** Removes this Android callback only when the exact scheduler callback is pending. */
    @Override
    public void cancelFrame(FrameCallback callback) {
        assertOwnerThread();
        FrameCallback checked = Objects.requireNonNull(callback, "callback");
        if (pendingCallback != checked) {
            return;
        }

        // Clear only after Android confirms removal. If removal fails, the exact request remains
        // cancellable and a later platform delivery can still consume it safely.
        choreographer.removeFrameCallback(this);
        pendingCallback = null;
    }

    /** Clears the one-shot request before forwarding the frame to the owner-confined scheduler. */
    @Override
    public void doFrame(long frameTimeNanos) {
        assertOwnerThread();
        FrameCallback callback = pendingCallback;
        if (callback == null) {
            return;
        }

        // Rearming from inside requestAnimationFrame must target a later frame without allocating
        // another Android adapter. Removing the current request before delivery makes that legal.
        pendingCallback = null;
        callback.onFrame(frameTimeNanos);
    }

    private void assertOwnerThread() {
        if (Looper.myLooper() != looper) {
            throw new IllegalStateException(
                    "Animation frame host accessed outside its Android Looper thread");
        }
    }
}
