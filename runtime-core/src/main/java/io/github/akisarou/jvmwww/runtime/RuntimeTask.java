package io.github.akisarou.jvmwww.runtime;

/**
 * A unit of work owned by one {@link RuntimeInstance}.
 *
 * <p>Promise reactions, compiler-generated continuations, logical timer callbacks, copied platform
 * events, and {@link PlatformPromise} completion tokens can implement this interface directly. The
 * runtime never creates one Java {@link Runnable} per reaction, logical timer, or platform
 * completion.</p>
 */
public interface RuntimeTask {
    /** Executes on the runtime owner thread. */
    void execute(RuntimeInstance runtime) throws Throwable;

    /**
     * Releases retained resources when a queued or scheduled registration is removed before its
     * next execution.
     *
     * <p>For a repeating registration this may run after earlier successful deliveries, when the
     * registration itself is finally cancelled. It may also run on a foreign producer thread after
     * the runtime has closed. It must not execute generated TypeScript or access owner-confined
     * runtime state, and it should not throw.</p>
     */
    default void discard() {}
}
