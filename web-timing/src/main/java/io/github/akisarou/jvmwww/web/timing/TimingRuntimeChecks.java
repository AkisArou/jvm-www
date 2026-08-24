package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

/** Shared owner/language-turn checks for Web timing objects. */
final class TimingRuntimeChecks {
    private TimingRuntimeChecks() {}

    static void assertLanguageExecution(RuntimeInstance runtime) {
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException("Web timing object accessed outside its runtime owner");
        }
        if (runtime.isClosed()) {
            throw new IllegalStateException("Web timing object runtime is closed");
        }
        if (!runtime.isInsideHostTurn() && !runtime.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "Web timing operation requires an active host turn or microtask");
        }
    }
}
