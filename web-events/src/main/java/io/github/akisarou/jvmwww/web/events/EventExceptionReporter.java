package io.github.akisarou.jvmwww.web.events;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

/** Receives exceptions reported by Event listeners and abort algorithms. */
@FunctionalInterface
public interface EventExceptionReporter {
    /**
     * Reports a non-fatal callback failure without changing dispatch control flow.
     *
     * <p>The hook runs on the owning {@link RuntimeInstance}. It must not re-enter generated
     * TypeScript unless the selected compatibility profile deliberately schedules a later host
     * task.</p>
     */
    void report(RuntimeInstance runtime, EventFailurePhase phase, Throwable error);
}
