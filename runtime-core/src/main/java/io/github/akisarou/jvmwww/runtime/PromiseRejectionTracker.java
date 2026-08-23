package io.github.akisarou.jvmwww.runtime;

/**
 * Host hook for unhandled and later-handled Promise rejections.
 *
 * <p>The hook runs on the runtime owner after a complete microtask checkpoint. It must not execute
 * generated TypeScript directly. A Web or Node compatibility layer can copy the notification into
 * an ordinary host task when it needs to dispatch user-visible events.</p>
 */
public interface PromiseRejectionTracker {
    PromiseRejectionTracker NONE = new PromiseRejectionTracker() {
        @Override
        public void onUnhandled(RuntimeInstance runtime, JsPromise promise) {}

        @Override
        public void onHandled(RuntimeInstance runtime, JsPromise promise) {}
    };

    /** Called once when a rejected Promise is still unhandled at a checkpoint. */
    void onUnhandled(RuntimeInstance runtime, JsPromise promise);

    /** Called once when a previously reported Promise later becomes handled. */
    void onHandled(RuntimeInstance runtime, JsPromise promise);
}
