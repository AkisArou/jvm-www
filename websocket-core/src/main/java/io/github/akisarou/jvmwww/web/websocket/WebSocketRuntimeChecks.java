package io.github.akisarou.jvmwww.web.websocket;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

final class WebSocketRuntimeChecks {
    private WebSocketRuntimeChecks() {}

    static void assertLanguageExecution(RuntimeInstance runtime) {
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException("WebSocket object accessed outside its runtime owner thread");
        }
        if (runtime.isClosed()) {
            throw new IllegalStateException("WebSocket runtime is closed");
        }
        if (!runtime.isInsideHostTurn() && !runtime.isDrainingMicrotasks()) {
            throw new IllegalStateException("WebSocket operation requires an active host turn or microtask");
        }
    }
}
