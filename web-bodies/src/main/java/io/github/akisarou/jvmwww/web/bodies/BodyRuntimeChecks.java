package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

final class BodyRuntimeChecks {
    private BodyRuntimeChecks() {}

    static void assertLanguageExecution(RuntimeInstance runtime) {
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException("Body object accessed outside its runtime owner thread");
        }
        if (runtime.isClosed()) {
            throw new IllegalStateException("Body object runtime is closed");
        }
        if (!runtime.isInsideHostTurn() && !runtime.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "Body operation requires an active host turn or microtask");
        }
    }
}
