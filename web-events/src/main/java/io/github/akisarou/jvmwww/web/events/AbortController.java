package io.github.akisarou.jvmwww.web.events;

import io.github.akisarou.jvmwww.runtime.JsThrownValue;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Objects;

/** Controller whose signal aborts synchronously on one runtime owner. */
public final class AbortController {
    private final AbortSignal signal;

    public AbortController(RuntimeInstance runtime) {
        this(runtime, DefaultEventExceptionReporter.INSTANCE);
    }

    public AbortController(RuntimeInstance runtime, EventExceptionReporter reporter) {
        RuntimeInstance checked = Objects.requireNonNull(runtime, "runtime");
        EventExceptionReporter checkedReporter = Objects.requireNonNull(reporter, "reporter");
        if (!checked.isOwnerThread()) {
            throw new IllegalStateException(
                    "AbortController created outside its runtime owner thread");
        }
        if (checked.isClosed()) {
            throw new IllegalStateException("AbortController runtime is closed");
        }
        if (!checked.isInsideHostTurn() && !checked.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "AbortController creation requires an active host turn or microtask");
        }
        signal = new AbortSignal(checked, checkedReporter);
    }

    public AbortSignal getSignal() {
        return signal;
    }

    /** Aborts with a newly created AbortError when no explicit reason is supplied. */
    public void abort() {
        signal.signalAbortReference(DOMException.abortError());
    }

    public void abort(double reason) {
        abortNumber(reason);
    }

    public void abortNumber(double reason) {
        signal.signalAbortNumber(reason);
    }

    public void abort(boolean reason) {
        abortBoolean(reason);
    }

    public void abortBoolean(boolean reason) {
        signal.signalAbortBoolean(reason);
    }

    public void abort(Object reason) {
        abortReference(reason);
    }

    public void abortReference(Object reason) {
        signal.signalAbortReference(reason);
    }

    public void abortThrown(JsThrownValue reason) {
        signal.signalAbortThrown(reason);
    }
}
