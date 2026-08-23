package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.OwnerExecutor;
import java.util.ArrayDeque;

/** Deterministic single-thread owner used by conformance tests. */
public final class ManualOwnerExecutor implements OwnerExecutor {
    private final Thread ownerThread;
    private final ArrayDeque<Runnable> callbacks = new ArrayDeque<Runnable>();
    private int postCount;

    public ManualOwnerExecutor() {
        ownerThread = Thread.currentThread();
    }

    @Override
    public boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    @Override
    public synchronized void post(Runnable callback) {
        callbacks.addLast(callback);
        postCount++;
    }

    public synchronized int getPostCount() {
        return postCount;
    }

    public synchronized int getPendingCallbackCount() {
        return callbacks.size();
    }

    public void runNext() {
        final Runnable callback;
        synchronized (this) {
            callback = callbacks.pollFirst();
        }
        if (callback == null) {
            throw new AssertionError("No owner callback is pending");
        }
        callback.run();
    }

    public void runAll() {
        while (true) {
            final Runnable callback;
            synchronized (this) {
                callback = callbacks.pollFirst();
            }
            if (callback == null) {
                return;
            }
            callback.run();
        }
    }
}
