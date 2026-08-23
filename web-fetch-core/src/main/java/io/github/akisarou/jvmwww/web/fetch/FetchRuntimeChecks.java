package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

final class FetchRuntimeChecks {
    private FetchRuntimeChecks() {}

    static void assertLanguageExecution(RuntimeInstance runtime) {
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException("Fetch object accessed outside its runtime owner thread");
        }
        if (runtime.isClosed()) {
            throw new IllegalStateException("Fetch object runtime is closed");
        }
        if (!runtime.isInsideHostTurn() && !runtime.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "Fetch operation requires an active host turn or microtask");
        }
    }
}
