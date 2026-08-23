package io.github.akisarou.jvmwww.web.events;

/** Host-side cancellation step registered with an {@link AbortSignal}. */
@FunctionalInterface
public interface AbortAlgorithm {
    /** Runs synchronously on the signal's runtime owner before the abort event is dispatched. */
    void run(AbortSignal signal) throws Throwable;
}
