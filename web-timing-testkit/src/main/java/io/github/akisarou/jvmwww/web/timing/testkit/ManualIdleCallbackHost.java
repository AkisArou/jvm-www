package io.github.akisarou.jvmwww.web.timing.testkit;

import io.github.akisarou.jvmwww.web.timing.IdleCallbackHost;

/** Deterministic owner-thread idle-period host for conformance tests. */
public final class ManualIdleCallbackHost implements IdleCallbackHost {
    private final Thread ownerThread = Thread.currentThread();
    private IdleCallback callback;
    private IdleCallback lastRequestedCallback;
    private long nowNanos;
    private int requestCount;
    private int cancelCount;
    private RuntimeException nextRequestFailure;
    private RuntimeException nextCancelFailure;

    @Override
    public long nowNanos() {
        assertOwner();
        return nowNanos;
    }

    @Override
    public void requestIdle(IdleCallback requestedCallback) {
        assertOwner();
        if (nextRequestFailure != null) {
            RuntimeException failure = nextRequestFailure;
            nextRequestFailure = null;
            throw failure;
        }
        if (callback != null) {
            throw new IllegalStateException("An idle callback is already pending");
        }
        callback = requestedCallback;
        lastRequestedCallback = requestedCallback;
        requestCount++;
    }

    @Override
    public void cancelIdle(IdleCallback cancelledCallback) {
        assertOwner();
        if (callback != cancelledCallback) {
            return;
        }
        if (nextCancelFailure != null) {
            RuntimeException failure = nextCancelFailure;
            nextCancelFailure = null;
            throw failure;
        }
        callback = null;
        cancelCount++;
    }

    public void setNowNanos(long value) {
        assertOwner();
        nowNanos = value;
    }

    public void advanceNanos(long amount) {
        assertOwner();
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        nowNanos += amount;
    }

    public void failNextRequest(RuntimeException failure) {
        assertOwner();
        nextRequestFailure = failure;
    }

    public void failNextCancel(RuntimeException failure) {
        assertOwner();
        nextCancelFailure = failure;
    }

    public boolean hasPendingIdle() {
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

    public IdleCallback getPendingCallback() {
        assertOwner();
        return callback;
    }

    public IdleCallback getLastRequestedCallback() {
        assertOwner();
        return lastRequestedCallback;
    }

    public void fireAt(long now, long deadlineNanos) {
        assertOwner();
        IdleCallback pending = callback;
        if (pending == null) {
            throw new AssertionError("No idle callback is pending");
        }
        callback = null;
        nowNanos = now;
        pending.onIdle(deadlineNanos);
    }

    private void assertOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Manual idle host accessed outside its owner thread");
        }
    }
}
