package io.github.akisarou.jvmwww.web.console;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

final class ConsoleRuntimeChecks {
    private ConsoleRuntimeChecks() {}

    static void assertLanguageExecution(RuntimeInstance runtime) {
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException("Console accessed outside its runtime owner thread");
        }
        if (runtime.isClosed()) {
            throw new IllegalStateException("Console runtime is closed");
        }
        if (!runtime.isInsideHostTurn() && !runtime.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "Console operation requires an active host turn or microtask");
        }
    }
}
