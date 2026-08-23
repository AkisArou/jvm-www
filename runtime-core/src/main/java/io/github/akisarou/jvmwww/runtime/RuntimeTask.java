package io.github.akisarou.jvmwww.runtime;

/**
 * A unit of work owned by one {@link RuntimeInstance}.
 *
 * <p>Promise reactions, compiler-generated continuations, and copied platform completion events
 * can implement this interface directly. The runtime never creates one Java {@link Runnable} per
 * reaction.</p>
 */
public interface RuntimeTask {
    /** Executes on the runtime owner thread. */
    void execute(RuntimeInstance runtime) throws Throwable;

    /**
     * Releases retained resources when the task is discarded before execution.
     *
     * <p>This method may run on a foreign producer thread after the runtime has closed. It must
     * not execute generated TypeScript or access owner-confined runtime state, and it should not
     * throw.</p>
     */
    default void discard() {}
}
