package android.view;

import android.os.Looper;

/** Minimal one-shot Choreographer model used only by web-timing-android-testkit. */
public final class Choreographer {
    public interface FrameCallback {
        void doFrame(long frameTimeNanos);
    }

    private static final ThreadLocal<Choreographer> CURRENT =
            new ThreadLocal<Choreographer>();

    private final Looper looper;
    private FrameCallback pendingCallback;
    private FrameCallback lastPostedCallback;
    private RuntimeException nextPostFailure;
    private RuntimeException nextRemoveFailure;
    private int postCount;
    private int removeCount;

    private Choreographer(Looper looper) {
        this.looper = looper;
    }

    public static Choreographer getInstance() {
        Looper looper = Looper.myLooper();
        if (looper == null) {
            throw new IllegalStateException("The current thread has no Looper");
        }
        Choreographer current = CURRENT.get();
        if (current == null) {
            current = new Choreographer(looper);
            CURRENT.set(current);
        }
        return current;
    }

    public void postFrameCallback(FrameCallback callback) {
        assertOwnerThread();
        if (nextPostFailure != null) {
            RuntimeException failure = nextPostFailure;
            nextPostFailure = null;
            throw failure;
        }
        if (callback == null) {
            throw new NullPointerException("callback");
        }
        if (pendingCallback != null) {
            throw new IllegalStateException("A fake frame callback is already pending");
        }
        pendingCallback = callback;
        lastPostedCallback = callback;
        postCount++;
    }

    public void removeFrameCallback(FrameCallback callback) {
        assertOwnerThread();
        if (nextRemoveFailure != null) {
            RuntimeException failure = nextRemoveFailure;
            nextRemoveFailure = null;
            throw failure;
        }
        if (pendingCallback == callback) {
            pendingCallback = null;
            removeCount++;
        }
    }

    public void fireFrameForTest(long frameTimeNanos) {
        assertOwnerThread();
        FrameCallback callback = pendingCallback;
        if (callback == null) {
            throw new AssertionError("No Choreographer frame callback is pending");
        }
        pendingCallback = null;
        callback.doFrame(frameTimeNanos);
    }

    public void invokeLastPostedForTest(long frameTimeNanos) {
        assertOwnerThread();
        if (lastPostedCallback == null) {
            throw new AssertionError("No Choreographer callback has been posted");
        }
        lastPostedCallback.doFrame(frameTimeNanos);
    }

    public void failNextPostForTest(RuntimeException failure) {
        assertOwnerThread();
        nextPostFailure = failure;
    }

    public void failNextRemoveForTest(RuntimeException failure) {
        assertOwnerThread();
        nextRemoveFailure = failure;
    }

    public boolean hasPendingFrameForTest() {
        assertOwnerThread();
        return pendingCallback != null;
    }

    public FrameCallback getPendingCallbackForTest() {
        assertOwnerThread();
        return pendingCallback;
    }

    public int getPostCountForTest() {
        assertOwnerThread();
        return postCount;
    }

    public int getRemoveCountForTest() {
        assertOwnerThread();
        return removeCount;
    }

    public void resetForTest() {
        assertOwnerThread();
        pendingCallback = null;
        lastPostedCallback = null;
        nextPostFailure = null;
        nextRemoveFailure = null;
        postCount = 0;
        removeCount = 0;
    }

    private void assertOwnerThread() {
        if (Looper.myLooper() != looper) {
            throw new IllegalStateException("Fake Choreographer accessed outside its Looper");
        }
    }
}
