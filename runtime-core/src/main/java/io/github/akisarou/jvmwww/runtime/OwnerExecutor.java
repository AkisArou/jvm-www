package io.github.akisarou.jvmwww.runtime;

/**
 * Schedules work on the single thread that owns a {@link RuntimeInstance}.
 *
 * <p>{@link #post(Runnable)} may be called from foreign threads. Implementations must preserve
 * FIFO order for calls made through the same executor and must enqueue the callback rather than
 * invoking it inline. Android will normally implement this with a {@code Handler} attached to the
 * runtime's {@code Looper}.</p>
 */
public interface OwnerExecutor {
    /** Returns whether the current thread is this executor's owner thread. */
    boolean isOwnerThread();

    /**
     * Enqueues a callback for later execution on the owner thread.
     *
     * <p>This operation must be thread-safe and asynchronous with respect to the caller. When it
     * throws, the callback must not have been enqueued; {@link RuntimeInstance} then removes and
     * discards the exact admission whose wake could not be published.</p>
     */
    void post(Runnable callback);
}
