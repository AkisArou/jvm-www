package io.github.akisarou.jvmwww.web.nativeelements;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

final class NativeElementRuntimeChecks {
    private NativeElementRuntimeChecks() {}

    static void assertLanguageExecution(RuntimeInstance runtime) {
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException(
                    "ReactNativeElement accessed outside its runtime owner thread");
        }
        if (runtime.isClosed()) {
            throw new IllegalStateException("ReactNativeElement runtime is closed");
        }
        if (!runtime.isInsideHostTurn() && !runtime.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "ReactNativeElement operation requires an active host turn or microtask");
        }
    }
}
