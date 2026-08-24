package io.github.akisarou.jvmwww.web.timing.testkit;

import io.github.akisarou.jvmwww.web.timing.AnimationFrameHost;

/** Deterministic owner-thread frame host for conformance tests. */
public final class ManualAnimationFrameHost implements AnimationFrameHost {
    private final Thread ownerThread = Thread.currentThread();
    private FrameCallback callback;
    private long nowNanos;
    private int requestCount;
    private int cancelCount;
    private RuntimeException nextRequestFailure;

    @Override
    public long nowNanos() {
        assertOwner();
        return nowNanos;
    }

    @Override
    public void requestFrame(FrameCallback requestedCallback) {
        assertOwner();
        if (nextRequestFailure != null) {
            RuntimeException failure = nextRequestFailure;
            nextRequestFailure = null;
            throw failure;
        }
        if (callback != null) {
            throw new IllegalStateException("A frame callback is already pending");
        }
        callback = requestedCallback;
        requestCount++;
    }

    @Override
    public void cancelFrame(FrameCallback cancelledCallback) {
        assertOwner();
        if (callback == cancelledCallback) {
            callback = null;
            cancelCount++;
        }
    }

    public void setNowNanos(long value) {
        assertOwner();
        nowNanos = value;
    }

    public void advanceNanos(long amount) {
        assertOwner();
        nowNanos += amount;
    }

    public void failNextRequest(RuntimeException failure) {
        assertOwner();
        nextRequestFailure = failure;
    }

    public boolean hasPendingFrame() {
        assertOwner();
        return callback != null;
    }

    public int getRequestCount() {
        assertOwner();
        return requestCount;
    }

    public int getCancelCount() {
        assertOwner();
        return cancelCount;
    }

    public void fireAt(long frameTimeNanos) {
        assertOwner();
        FrameCallback pending = callback;
        if (pending == null) {
            throw new AssertionError("No animation frame is pending");
        }
        callback = null;
        nowNanos = frameTimeNanos;
        pending.onFrame(frameTimeNanos);
    }

    private void assertOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Manual frame host accessed outside its owner thread");
        }
    }
}
