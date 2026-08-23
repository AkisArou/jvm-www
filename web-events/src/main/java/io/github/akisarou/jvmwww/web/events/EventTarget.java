package io.github.akisarou.jvmwww.web.events;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Objects;

/**
 * Owner-confined EventTarget for the selected Web Mobile capability profile.
 *
 * <p>Author-created targets have no parent tree. Dispatch therefore invokes capture listeners at
 * the target first, followed by non-capture listeners at that same target. Listener entries remain
 * intrusive tombstones while dispatch is nested, so removals are visible without cloning the list
 * and additions are excluded by a sequence cutoff.</p>
 */
public class EventTarget {
    private final RuntimeInstance runtime;
    private final EventExceptionReporter exceptionReporter;

    private ListenerEntry listenersHead;
    private ListenerEntry listenersTail;
    private long nextListenerSequence = 1L;
    private int dispatchDepth;

    public EventTarget(RuntimeInstance runtime) {
        this(runtime, DefaultEventExceptionReporter.INSTANCE);
    }

    public EventTarget(RuntimeInstance runtime, EventExceptionReporter exceptionReporter) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.exceptionReporter =
                Objects.requireNonNull(exceptionReporter, "exceptionReporter");
    }

    public final RuntimeInstance getRuntime() {
        return runtime;
    }

    protected final EventExceptionReporter getExceptionReporter() {
        return exceptionReporter;
    }

    public final void addEventListener(String type, EventListener callback) {
        addEventListenerImpl(type, callback, false, false, false, null);
    }

    public final void addEventListener(
            String type,
            EventListener callback,
            boolean capture) {
        addEventListenerImpl(type, callback, capture, false, false, null);
    }

    /** Static-profile lowering path that avoids allocating an options object. */
    public final void addEventListener(
            String type,
            EventListener callback,
            boolean capture,
            boolean passive,
            boolean once,
            AbortSignal signal) {
        addEventListenerImpl(type, callback, capture, passive, once, signal);
    }

    public void addEventListener(
            String type,
            EventListener callback,
            AddEventListenerOptions options) {
        AddEventListenerOptions checked =
                options == null ? AddEventListenerOptions.DEFAULT : options;
        addEventListenerImpl(
                type,
                callback,
                checked.isCapture(),
                checked.isPassive(),
                checked.isOnce(),
                checked.getSignal());
    }

    private void addEventListenerImpl(
            String type,
            EventListener callback,
            boolean capture,
            boolean passive,
            boolean once,
            AbortSignal signal) {
        assertLanguageExecution();
        String checkedType = Objects.requireNonNull(type, "type");
        if (callback == null) {
            return;
        }
        if (signal != null) {
            if (signal.getRuntime() != runtime) {
                throw new IllegalArgumentException(
                        "An event listener AbortSignal belongs to another RuntimeInstance");
            }
            if (signal.isAborted()) {
                return;
            }
        }

        ListenerEntry existing = listenersHead;
        while (existing != null) {
            if (!existing.removed
                    && existing.capture == capture
                    && existing.callback == callback
                    && existing.type.equals(checkedType)) {
                return;
            }
            existing = existing.next;
        }

        long sequence = allocateListenerSequence();
        ListenerEntry entry =
                new ListenerEntry(
                        this,
                        checkedType,
                        callback,
                        capture,
                        passive,
                        once,
                        signal,
                        sequence);
        appendEntry(entry);
        if (signal != null) {
            signal.addAbortAlgorithmInternal(entry);
        }
        eventListenerListChanged(checkedType);
    }

    public final void removeEventListener(String type, EventListener callback) {
        removeEventListenerImpl(type, callback, false);
    }

    public final void removeEventListener(
            String type,
            EventListener callback,
            boolean capture) {
        removeEventListenerImpl(type, callback, capture);
    }

    public void removeEventListener(
            String type,
            EventListener callback,
            EventListenerOptions options) {
        removeEventListenerImpl(type, callback, options != null && options.isCapture());
    }

    private void removeEventListenerImpl(
            String type,
            EventListener callback,
            boolean capture) {
        assertLanguageExecution();
        String checkedType = Objects.requireNonNull(type, "type");
        if (callback == null) {
            return;
        }

        ListenerEntry entry = listenersHead;
        while (entry != null) {
            if (!entry.removed
                    && entry.capture == capture
                    && entry.callback == callback
                    && entry.type.equals(checkedType)) {
                removeEntry(entry);
                return;
            }
            entry = entry.next;
        }
    }

    /**
     * Dispatches one synthetic event synchronously on the runtime owner.
     *
     * @return false exactly when a cancelable event was canceled
     */
    public boolean dispatchEvent(Event event) {
        assertLanguageExecution();
        Event checked = Objects.requireNonNull(event, "event");
        if (checked.isDispatching() || !checked.isInitializedForDispatch()) {
            throw DOMException.invalidState(
                    "The Event is already being dispatched or was not initialized");
        }

        checked.beginDispatch(this);
        dispatchDepth++;
        try {
            // The DOM dispatch algorithm clones the listener list once for each invocation target
            // and phase. A sequence cutoff is the allocation-free equivalent: additions made
            // during capture can participate in the later at-target bubble invocation, while
            // additions made during one invocation never enter that invocation's traversal.
            long captureCutoff = nextListenerSequence - 1L;
            invokeListeners(checked, true, captureCutoff);
            if (!checked.isPropagationStopped()) {
                long bubbleCutoff = nextListenerSequence - 1L;
                invokeListeners(checked, false, bubbleCutoff);
            }
            return !checked.isDefaultPrevented();
        } finally {
            checked.finishDispatch();
            dispatchDepth--;
            if (dispatchDepth == 0) {
                purgeRemovedEntries();
            }
        }
    }

    /** Called after an active listener of {@code type} is added or removed. */
    protected void eventListenerListChanged(String type) {}

    protected final boolean hasActiveEventListener(String type) {
        ListenerEntry entry = listenersHead;
        while (entry != null) {
            if (!entry.removed && entry.type.equals(type)) {
                return true;
            }
            entry = entry.next;
        }
        return false;
    }

    protected final void assertLanguageExecution() {
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException("EventTarget accessed outside its runtime owner thread");
        }
        if (runtime.isClosed()) {
            throw new IllegalStateException("EventTarget runtime is closed");
        }
        if (!runtime.isInsideHostTurn() && !runtime.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "EventTarget operation requires an active host turn or microtask");
        }
    }

    private void invokeListeners(Event event, boolean capture, long listenerCutoff) {
        ListenerEntry entry = listenersHead;
        while (entry != null) {
            ListenerEntry next = entry.next;
            if (entry.sequence > listenerCutoff) {
                break;
            }
            if (!entry.removed
                    && entry.capture == capture
                    && entry.type.equals(event.getType())) {
                if (entry.once) {
                    removeEntry(entry);
                }
                event.setPassiveListener(entry.passive);
                try {
                    entry.callback.handleEvent(event);
                } catch (Throwable error) {
                    rethrowIfFatal(error);
                    report(EventFailurePhase.EVENT_LISTENER, error);
                } finally {
                    event.setPassiveListener(false);
                }
                if (event.isImmediatePropagationStopped()) {
                    return;
                }
            }
            entry = next;
        }
    }

    private long allocateListenerSequence() {
        if (nextListenerSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Event listener sequence space exhausted");
        }
        return nextListenerSequence++;
    }

    private void appendEntry(ListenerEntry entry) {
        if (listenersTail == null) {
            listenersHead = entry;
        } else {
            listenersTail.next = entry;
            entry.previous = listenersTail;
        }
        listenersTail = entry;
    }

    private void removeEntry(ListenerEntry entry) {
        if (entry.removed) {
            return;
        }
        entry.removed = true;
        if (entry.signal != null) {
            entry.signal.removeAbortAlgorithmInternal(entry);
            entry.signal = null;
        }
        if (dispatchDepth == 0) {
            unlinkEntry(entry);
        }
        eventListenerListChanged(entry.type);
    }

    private void purgeRemovedEntries() {
        ListenerEntry entry = listenersHead;
        while (entry != null) {
            ListenerEntry next = entry.next;
            if (entry.removed) {
                unlinkEntry(entry);
            }
            entry = next;
        }
    }

    private void unlinkEntry(ListenerEntry entry) {
        ListenerEntry previous = entry.previous;
        ListenerEntry next = entry.next;
        if (previous == null) {
            listenersHead = next;
        } else {
            previous.next = next;
        }
        if (next == null) {
            listenersTail = previous;
        } else {
            next.previous = previous;
        }
        entry.previous = null;
        entry.next = null;
    }

    private void removeFromAbort(ListenerEntry entry) {
        assertLanguageExecution();
        removeEntry(entry);
    }

    protected final void report(EventFailurePhase phase, Throwable error) {
        try {
            exceptionReporter.report(runtime, phase, error);
        } catch (Throwable reporterFailure) {
            rethrowIfFatal(reporterFailure);
            if (reporterFailure != error) {
                error.addSuppressed(reporterFailure);
            }
            DefaultEventExceptionReporter.INSTANCE.report(runtime, phase, error);
        }
    }

    static void rethrowIfFatal(Throwable error) {
        if (error instanceof ThreadDeath) {
            throw (ThreadDeath) error;
        }
        if (error instanceof VirtualMachineError) {
            throw (VirtualMachineError) error;
        }
        if (error instanceof LinkageError) {
            throw (LinkageError) error;
        }
    }

    /** One allocation per listener registration; also its AbortSignal removal algorithm. */
    static final class ListenerEntry implements AbortAlgorithm {
        final EventTarget target;
        final String type;
        final EventListener callback;
        final boolean capture;
        final boolean passive;
        final boolean once;
        final long sequence;
        ListenerEntry previous;
        ListenerEntry next;
        AbortSignal signal;
        boolean removed;

        ListenerEntry(
                EventTarget target,
                String type,
                EventListener callback,
                boolean capture,
                boolean passive,
                boolean once,
                AbortSignal signal,
                long sequence) {
            this.target = target;
            this.type = type;
            this.callback = callback;
            this.capture = capture;
            this.passive = passive;
            this.once = once;
            this.signal = signal;
            this.sequence = sequence;
        }

        @Override
        public void run(AbortSignal source) {
            target.removeFromAbort(this);
        }
    }
}
