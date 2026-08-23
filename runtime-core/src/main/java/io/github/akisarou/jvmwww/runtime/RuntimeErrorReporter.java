package io.github.akisarou.jvmwww.runtime;

/**
 * Receives uncaught host-task, microtask, and cleanup failures.
 *
 * <p>Implementations must be thread-safe. Cleanup failures may be reported by a foreign producer
 * after the runtime has stopped accepting work.</p>
 */
public interface RuntimeErrorReporter {
    /**
     * Reports a failure without throwing.
     *
     * <p>Promise reaction exceptions are expected to reject their destination promise before they
     * reach this hook. A direct {@code queueMicrotask} callback exception does reach this hook.</p>
     */
    void report(RuntimeInstance runtime, RuntimeErrorPhase phase, Throwable error);
}
