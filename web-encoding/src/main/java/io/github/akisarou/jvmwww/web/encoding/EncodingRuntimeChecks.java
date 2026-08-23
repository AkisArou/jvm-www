package io.github.akisarou.jvmwww.web.encoding;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

final class EncodingRuntimeChecks {
    private EncodingRuntimeChecks() {}

    static void assertLanguageExecution(RuntimeInstance runtime) {
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException(
                    "Encoding object accessed outside its runtime owner thread");
        }
        if (runtime.isClosed()) {
            throw new IllegalStateException("Encoding object runtime is closed");
        }
        if (!runtime.isInsideHostTurn() && !runtime.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "Encoding operation requires an active host turn or microtask");
        }
    }
}
