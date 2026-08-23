package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.PromiseRejectionTracker;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records owner-checkpoint Promise rejection notifications for conformance tests. */
public final class RecordingPromiseRejectionTracker implements PromiseRejectionTracker {
    private final List<JsPromise> unhandled = new ArrayList<JsPromise>();
    private final List<JsPromise> handled = new ArrayList<JsPromise>();

    @Override
    public void onUnhandled(RuntimeInstance runtime, JsPromise promise) {
        unhandled.add(promise);
    }

    @Override
    public void onHandled(RuntimeInstance runtime, JsPromise promise) {
        handled.add(promise);
    }

    public List<JsPromise> getUnhandled() {
        return Collections.unmodifiableList(unhandled);
    }

    public List<JsPromise> getHandled() {
        return Collections.unmodifiableList(handled);
    }
}
