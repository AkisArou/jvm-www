package io.github.akisarou.jvmwww.web.timing.android;

import android.os.Looper;
import android.os.MessageQueue;
import io.github.akisarou.jvmwww.web.timing.IdleCallbackHost;
import java.util.Objects;

/**
 * Binds one {@link IdleCallbackHost} to the current Android Looper's message queue.
 *
 * <p>The host object itself is the reusable {@link MessageQueue.IdleHandler}. It retains at most
 * one scheduler callback and creates no wrapper, Runnable, or registration object for an idle
 * request.</p>
 */
public final class MessageQueueIdleCallbackHost
        implements IdleCallbackHost, MessageQueue.IdleHandler {
    /** Conservative default because queueIdle may run while future messages are already pending. */
    public static final long DEFAULT_IDLE_BUDGET_NANOS = 1_000_000L;

    private final Looper looper;
    private final MessageQueue messageQueue;
    private final long idleBudgetNanos;
    private IdleCallback pendingCallback;
    private boolean insideQueueIdle;

    private MessageQueueIdleCallbackHost(
            Looper looper,
            MessageQueue messageQueue,
            long idleBudgetNanos) {
        this.looper = Objects.requireNonNull(looper, "looper");
        this.messageQueue = Objects.requireNonNull(messageQueue, "messageQueue");
        if (idleBudgetNanos <= 0L) {
            throw new IllegalArgumentException("idleBudgetNanos must be positive");
        }
        this.idleBudgetNanos = idleBudgetNanos;
    }

    /** Creates a host on the calling thread's Looper with a one-millisecond idle budget. */
    public static MessageQueueIdleCallbackHost forCurrentLooper() {
        return forCurrentLooper(DEFAULT_IDLE_BUDGET_NANOS);
    }

    /** Creates a host on the calling thread's Looper with an explicit nanosecond budget. */
    public static MessageQueueIdleCallbackHost forCurrentLooper(long idleBudgetNanos) {
        Looper looper = Looper.myLooper();
        if (looper == null) {
            throw new IllegalStateException("Current thread has no Android Looper");
        }
        return new MessageQueueIdleCallbackHost(looper, Looper.myQueue(), idleBudgetNanos);
    }

    public Looper getLooper() {
        return looper;
    }

    public long getIdleBudgetNanos() {
        return idleBudgetNanos;
    }

    /** Uses the same arbitrary monotonic nanosecond domain as each delivered idle deadline. */
    @Override
    public long nowNanos() {
        assertOwnerThread();
        return System.nanoTime();
    }

    /** Registers this host directly, unless the current queueIdle call will retain it. */
    @Override
    public void requestIdle(IdleCallback callback) {
        assertOwnerThread();
        IdleCallback checked = Objects.requireNonNull(callback, "callback");
        if (pendingCallback != null) {
            throw new IllegalStateException("An idle callback is already pending");
        }

        pendingCallback = checked;
        if (insideQueueIdle) {
            // queueIdle() will return true. Adding the same object again while Android is iterating
            // its idle-handler snapshot would create an unnecessary duplicate registration.
            return;
        }
        try {
            messageQueue.addIdleHandler(this);
        } catch (RuntimeException error) {
            pendingCallback = null;
            throw error;
        } catch (Error error) {
            pendingCallback = null;
            throw error;
        }
    }

    /** Removes this handler only while the exact scheduler callback remains pending. */
    @Override
    public void cancelIdle(IdleCallback callback) {
        assertOwnerThread();
        IdleCallback checked = Objects.requireNonNull(callback, "callback");
        if (pendingCallback != checked) {
            return;
        }
        if (insideQueueIdle) {
            // Returning false removes this handler after the current callback. A later request in
            // the same delivery can still replace the field and make queueIdle() return true.
            pendingCallback = null;
            return;
        }

        // Clear only after Android confirms removal. If removal throws, the exact callback remains
        // retained so cancellation can be retried and a late delivery remains safe.
        messageQueue.removeIdleHandler(this);
        pendingCallback = null;
    }

    /** Delivers one notification and remains installed only when the core rearmed during it. */
    @Override
    public boolean queueIdle() {
        assertOwnerThread();
        IdleCallback callback = pendingCallback;
        if (callback == null) {
            return false;
        }

        pendingCallback = null;
        insideQueueIdle = true;
        try {
            long now = System.nanoTime();
            callback.onIdle(saturatingAdd(now, idleBudgetNanos));
        } finally {
            insideQueueIdle = false;
        }
        return pendingCallback != null;
    }

    private void assertOwnerThread() {
        if (Looper.myLooper() != looper) {
            throw new IllegalStateException(
                    "Idle callback host accessed outside its Android Looper thread");
        }
    }

    private static long saturatingAdd(long value, long positiveIncrement) {
        return value > Long.MAX_VALUE - positiveIncrement
                ? Long.MAX_VALUE
                : value + positiveIncrement;
    }
}
