package io.github.akisarou.jvmwww.web.events;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsThrownValue;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Web-compatible AbortSignal specialized for one {@link RuntimeInstance}.
 *
 * <p>The signal is owner-confined for observable state. It is also its own timeout task and its
 * own {@code onabort} listener, avoiding a timer wrapper and a separate event-handler adapter.</p>
 */
public final class AbortSignal extends EventTarget implements RuntimeTask, EventListener {
    private static final String ABORT_EVENT_TYPE = "abort";
    private static final double MAX_TIMER_DELAY_MILLIS = 2_147_483_647.0;

    private boolean aborted;
    private int reasonKind = JsPromise.PAYLOAD_VOID;
    private double reasonNumber;
    private boolean reasonBoolean;
    private Object reasonReference;

    private ArrayList<AbortAlgorithm> abortAlgorithms;
    private int activeAbortAlgorithmCount;
    private boolean runningAbortAlgorithms;

    private EventListener onAbort;
    private double timeoutHandle;

    /** Original source signals for AbortSignal.any, in input/flattened order. */
    private AbortSignal[] sourceSignals;
    private boolean retainedBySources;

    /** Ordered source-side edges, weak until each dependent has abort observers. */
    private ArrayList<AbortSignalDependency> dependents;

    AbortSignal(RuntimeInstance runtime, EventExceptionReporter reporter) {
        super(runtime, reporter);
    }

    public boolean isAborted() {
        assertLanguageExecution();
        return aborted;
    }

    /** Returns the specialized reason kind; inspect {@link #isAborted()} separately. */
    public int getReasonKind() {
        assertLanguageExecution();
        return reasonKind;
    }

    public double getReasonNumber() {
        assertLanguageExecution();
        requireReasonKind(JsPromise.PAYLOAD_NUMBER);
        return reasonNumber;
    }

    public boolean getReasonBoolean() {
        assertLanguageExecution();
        requireReasonKind(JsPromise.PAYLOAD_BOOLEAN);
        return reasonBoolean;
    }

    public Object getReasonReference() {
        assertLanguageExecution();
        requireReasonKind(JsPromise.PAYLOAD_REFERENCE);
        return reasonReference;
    }

    /** Generic convenience view; generated typed code should use the specialized getters. */
    public Object getReason() {
        assertLanguageExecution();
        if (!aborted || reasonKind == JsPromise.PAYLOAD_VOID) {
            return null;
        }
        if (reasonKind == JsPromise.PAYLOAD_NUMBER) {
            return Double.valueOf(reasonNumber);
        }
        if (reasonKind == JsPromise.PAYLOAD_BOOLEAN) {
            return Boolean.valueOf(reasonBoolean);
        }
        return reasonReference;
    }

    /** Throws the exact arbitrary abort reason through the runtime's JavaScript-value carrier. */
    public void throwIfAborted() {
        assertLanguageExecution();
        if (!aborted) {
            return;
        }
        switch (reasonKind) {
            case JsPromise.PAYLOAD_VOID:
                throw JsThrownValue.voidValue();
            case JsPromise.PAYLOAD_NUMBER:
                throw JsThrownValue.number(reasonNumber);
            case JsPromise.PAYLOAD_BOOLEAN:
                throw JsThrownValue.bool(reasonBoolean);
            case JsPromise.PAYLOAD_REFERENCE:
                throw JsThrownValue.reference(reasonReference);
            default:
                throw new AssertionError("Unknown abort reason kind: " + reasonKind);
        }
    }

    public EventListener getOnAbort() {
        assertLanguageExecution();
        return onAbort;
    }

    /**
     * Sets the event-handler attribute without moving its listener-list position on replacement.
     */
    public void setOnAbort(EventListener callback) {
        assertLanguageExecution();
        if (onAbort == callback) {
            return;
        }
        if (onAbort == null && callback != null) {
            onAbort = callback;
            addEventListener(ABORT_EVENT_TYPE, this);
            return;
        }
        if (callback == null) {
            onAbort = null;
            removeEventListener(ABORT_EVENT_TYPE, this);
            return;
        }
        onAbort = callback;
    }

    /** Invokes the currently installed event-handler attribute. */
    @Override
    public void handleEvent(Event event) throws Throwable {
        EventListener callback = onAbort;
        if (callback != null) {
            callback.handleEvent(event);
        }
    }

    /**
     * Registers one host cancellation step.
     *
     * @return false when the signal was already aborted and the algorithm was not registered
     */
    public boolean addAbortAlgorithm(AbortAlgorithm algorithm) {
        assertLanguageExecution();
        return addAbortAlgorithmInternal(Objects.requireNonNull(algorithm, "algorithm"));
    }

    public void removeAbortAlgorithm(AbortAlgorithm algorithm) {
        assertLanguageExecution();
        if (algorithm != null) {
            removeAbortAlgorithmInternal(algorithm);
        }
    }

    boolean addAbortAlgorithmInternal(AbortAlgorithm algorithm) {
        if (aborted) {
            return false;
        }
        if (abortAlgorithms == null) {
            abortAlgorithms = new ArrayList<AbortAlgorithm>(4);
        } else {
            for (AbortAlgorithm existing : abortAlgorithms) {
                if (existing == algorithm) {
                    return true;
                }
            }
        }
        abortAlgorithms.add(algorithm);
        activeAbortAlgorithmCount++;
        refreshDependentRetention();
        return true;
    }

    void removeAbortAlgorithmInternal(AbortAlgorithm algorithm) {
        if (abortAlgorithms == null || activeAbortAlgorithmCount == 0) {
            return;
        }
        for (int index = 0; index < abortAlgorithms.size(); index++) {
            if (abortAlgorithms.get(index) == algorithm) {
                abortAlgorithms.set(index, null);
                activeAbortAlgorithmCount--;
                if (!runningAbortAlgorithms) {
                    compactAbortAlgorithms();
                }
                refreshDependentRetention();
                return;
            }
        }
    }

    @Override
    protected void eventListenerListChanged(String type) {
        if (ABORT_EVENT_TYPE.equals(type)) {
            refreshDependentRetention();
        }
    }

    /** TimerHost callback path for AbortSignal.timeout. */
    @Override
    public void execute(RuntimeInstance runtime) {
        if (runtime != getRuntime()) {
            throw new IllegalArgumentException("AbortSignal timeout delivered by another runtime");
        }
        timeoutHandle = 0.0;
        signalAbortReference(DOMException.timeoutError());
    }

    /** A cancelled timeout registration must not abort the signal. */
    @Override
    public void discard() {
        timeoutHandle = 0.0;
    }

    public static AbortSignal abort(RuntimeInstance runtime) {
        return abortWithReporter(runtime, DefaultEventExceptionReporter.INSTANCE);
    }

    public static AbortSignal abortWithReporter(
            RuntimeInstance runtime,
            EventExceptionReporter reporter) {
        AbortSignal signal = newSignal(runtime, reporter);
        signal.initializeAbortedReference(DOMException.abortError());
        return signal;
    }

    public static AbortSignal abort(RuntimeInstance runtime, double reason) {
        return abortNumber(runtime, reason);
    }

    public static AbortSignal abortNumber(RuntimeInstance runtime, double reason) {
        AbortSignal signal = newSignal(runtime, DefaultEventExceptionReporter.INSTANCE);
        signal.initializeAbortedNumber(reason);
        return signal;
    }

    public static AbortSignal abort(RuntimeInstance runtime, boolean reason) {
        return abortBoolean(runtime, reason);
    }

    public static AbortSignal abortBoolean(RuntimeInstance runtime, boolean reason) {
        AbortSignal signal = newSignal(runtime, DefaultEventExceptionReporter.INSTANCE);
        signal.initializeAbortedBoolean(reason);
        return signal;
    }

    public static AbortSignal abort(RuntimeInstance runtime, Object reason) {
        return abortReference(runtime, reason);
    }

    public static AbortSignal abortReference(RuntimeInstance runtime, Object reason) {
        AbortSignal signal = newSignal(runtime, DefaultEventExceptionReporter.INSTANCE);
        signal.initializeAbortedReference(reason);
        return signal;
    }

    /**
     * Creates a timeout signal using the runtime's one logical timer heap.
     *
     * <p>The current checked mobile timer profile supports finite delays through 2^31-1 ms and
     * applies its existing integer-millisecond coercion. Larger or invalid values are refused
     * rather than silently wrapped into the Node timeout clamp.</p>
     */
    public static AbortSignal timeout(RuntimeInstance runtime, double milliseconds) {
        return timeoutWithReporter(runtime, milliseconds, DefaultEventExceptionReporter.INSTANCE);
    }

    public static AbortSignal timeoutWithReporter(
            RuntimeInstance runtime,
            double milliseconds,
            EventExceptionReporter reporter) {
        if (Double.isNaN(milliseconds)
                || Double.isInfinite(milliseconds)
                || milliseconds < 0.0
                || milliseconds > MAX_TIMER_DELAY_MILLIS) {
            throw new IllegalArgumentException(
                    "AbortSignal.timeout delay must be finite and between 0 and 2^31-1 ms");
        }
        AbortSignal signal = newSignal(runtime, reporter);
        long coercedMilliseconds = (long) milliseconds;
        if (coercedMilliseconds == 0L) {
            // [EnforceRange] truncates a fractional sub-millisecond value to zero. Zero active
            // time is still asynchronous, so admit the signal itself as a later host task.
            runtime.admitHostTask(signal);
        } else {
            signal.timeoutHandle = runtime.setTimeout(signal, coercedMilliseconds);
        }
        return signal;
    }

    public static AbortSignal any(RuntimeInstance runtime, AbortSignal... signals) {
        return anyWithReporter(runtime, DefaultEventExceptionReporter.INSTANCE, signals);
    }

    public static AbortSignal anyWithReporter(
            RuntimeInstance runtime,
            EventExceptionReporter reporter,
            AbortSignal... signals) {
        AbortSignal result = newSignal(runtime, reporter);
        AbortSignal[] checked = Objects.requireNonNull(signals, "signals");
        ArrayList<AbortSignal> flattened = new ArrayList<AbortSignal>(checked.length);

        for (AbortSignal signal : checked) {
            AbortSignal source = Objects.requireNonNull(signal, "signals contains null");
            if (source.getRuntime() != runtime) {
                throw new IllegalArgumentException(
                        "AbortSignal.any cannot combine different RuntimeInstances");
            }
            source.assertLanguageExecution();
            if (source.aborted) {
                result.initializeAbortedFrom(source);
                return result;
            }
            source.appendFlattenedSources(flattened);
        }

        if (flattened.isEmpty()) {
            return result;
        }
        result.sourceSignals = flattened.toArray(new AbortSignal[flattened.size()]);
        for (AbortSignal source : result.sourceSignals) {
            source.addDependent(result, false);
        }
        return result;
    }

    boolean signalAbortReference(Object reason) {
        assertLanguageExecution();
        return signalAbort(
                JsPromise.PAYLOAD_REFERENCE,
                0.0,
                false,
                reason);
    }

    boolean signalAbortNumber(double reason) {
        assertLanguageExecution();
        return signalAbort(JsPromise.PAYLOAD_NUMBER, reason, false, null);
    }

    boolean signalAbortBoolean(boolean reason) {
        assertLanguageExecution();
        return signalAbort(JsPromise.PAYLOAD_BOOLEAN, 0.0, reason, null);
    }

    boolean signalAbortThrown(JsThrownValue reason) {
        assertLanguageExecution();
        Objects.requireNonNull(reason, "reason");
        switch (reason.getPayloadKind()) {
            case JsPromise.PAYLOAD_VOID:
                return signalAbort(
                        JsPromise.PAYLOAD_REFERENCE,
                        0.0,
                        false,
                        DOMException.abortError());
            case JsPromise.PAYLOAD_NUMBER:
                return signalAbort(
                        JsPromise.PAYLOAD_NUMBER,
                        reason.getNumberPayload(),
                        false,
                        null);
            case JsPromise.PAYLOAD_BOOLEAN:
                return signalAbort(
                        JsPromise.PAYLOAD_BOOLEAN,
                        0.0,
                        reason.getBooleanPayload(),
                        null);
            case JsPromise.PAYLOAD_REFERENCE:
                return signalAbort(
                        JsPromise.PAYLOAD_REFERENCE,
                        0.0,
                        false,
                        reason.getReferencePayload());
            default:
                throw new AssertionError("Unknown thrown reason kind: " + reason.getPayloadKind());
        }
    }

    private boolean signalAbort(
            int kind,
            double number,
            boolean bool,
            Object reference) {
        if (aborted) {
            return false;
        }
        setReason(kind, number, bool, reference);
        cancelTimeoutIfNeeded();
        detachFromSources();

        ArrayList<AbortSignal> dependents = new ArrayList<AbortSignal>();
        collectAndMarkDependents(dependents);

        runAbortSteps();
        for (int index = 0; index < dependents.size(); index++) {
            AbortSignal dependent = dependents.get(index);
            dependent.runAbortSteps();
        }
        return true;
    }

    private void collectAndMarkDependents(ArrayList<AbortSignal> output) {
        ArrayList<AbortSignal> direct = new ArrayList<AbortSignal>();
        collectDirectDependents(direct);
        for (int index = 0; index < direct.size(); index++) {
            AbortSignal dependent = direct.get(index);
            if (dependent.aborted || containsIdentity(output, dependent)) {
                continue;
            }
            dependent.copyReasonFrom(this);
            dependent.cancelTimeoutIfNeeded();
            dependent.detachFromSources();
            output.add(dependent);
            dependent.collectAndMarkDependents(output);
        }
    }

    private void runAbortSteps() {
        runAbortAlgorithms();
        dispatchEvent(new Event(ABORT_EVENT_TYPE));
    }

    private void runAbortAlgorithms() {
        if (abortAlgorithms == null || activeAbortAlgorithmCount == 0) {
            return;
        }
        runningAbortAlgorithms = true;
        int end = abortAlgorithms.size();
        try {
            for (int index = 0; index < end; index++) {
                AbortAlgorithm algorithm = abortAlgorithms.get(index);
                if (algorithm == null) {
                    continue;
                }
                abortAlgorithms.set(index, null);
                activeAbortAlgorithmCount--;
                try {
                    algorithm.run(this);
                } catch (Throwable error) {
                    EventTarget.rethrowIfFatal(error);
                    report(EventFailurePhase.ABORT_ALGORITHM, error);
                }
            }
        } finally {
            runningAbortAlgorithms = false;
            abortAlgorithms.clear();
            activeAbortAlgorithmCount = 0;
        }
    }

    private void compactAbortAlgorithms() {
        if (abortAlgorithms == null || abortAlgorithms.isEmpty()) {
            return;
        }
        int write = 0;
        for (int read = 0; read < abortAlgorithms.size(); read++) {
            AbortAlgorithm algorithm = abortAlgorithms.get(read);
            if (algorithm != null) {
                if (write != read) {
                    abortAlgorithms.set(write, algorithm);
                }
                write++;
            }
        }
        while (abortAlgorithms.size() > write) {
            abortAlgorithms.remove(abortAlgorithms.size() - 1);
        }
    }

    private void appendFlattenedSources(ArrayList<AbortSignal> output) {
        if (sourceSignals == null || sourceSignals.length == 0) {
            addIdentityOnce(output, this);
            return;
        }
        for (AbortSignal source : sourceSignals) {
            addIdentityOnce(output, source);
        }
    }

    private void addDependent(AbortSignal dependent, boolean strong) {
        if (dependents == null) {
            dependents = new ArrayList<AbortSignalDependency>(2);
        }
        cleanDependencies();
        AbortSignalDependency existing = findDependency(dependent);
        if (existing != null) {
            existing.setRetainedStrongly(strong);
            return;
        }
        dependents.add(new AbortSignalDependency(dependent, strong));
    }

    private void promoteDependent(AbortSignal dependent) {
        AbortSignalDependency link = findDependency(dependent);
        if (link != null) {
            link.setRetainedStrongly(true);
        }
    }

    private void demoteDependent(AbortSignal dependent) {
        AbortSignalDependency link = findDependency(dependent);
        if (link != null) {
            link.setRetainedStrongly(false);
        }
    }

    private void removeDependent(AbortSignal dependent) {
        if (dependents == null) {
            return;
        }
        for (int index = dependents.size() - 1; index >= 0; index--) {
            AbortSignal candidate = dependents.get(index).getSignal();
            if (candidate == null || candidate == dependent) {
                dependents.remove(index);
            }
        }
    }

    private void collectDirectDependents(ArrayList<AbortSignal> output) {
        if (dependents == null) {
            return;
        }
        for (AbortSignalDependency dependency : dependents) {
            AbortSignal dependent = dependency.getSignal();
            if (dependent != null) {
                addIdentityOnce(output, dependent);
            }
        }
        dependents.clear();
    }

    private AbortSignalDependency findDependency(AbortSignal dependent) {
        if (dependents == null) {
            return null;
        }
        for (AbortSignalDependency dependency : dependents) {
            if (dependency.getSignal() == dependent) {
                return dependency;
            }
        }
        return null;
    }

    private void cleanDependencies() {
        if (dependents == null) {
            return;
        }
        for (int index = dependents.size() - 1; index >= 0; index--) {
            if (dependents.get(index).getSignal() == null) {
                dependents.remove(index);
            }
        }
    }

    private void refreshDependentRetention() {
        if (aborted || sourceSignals == null) {
            return;
        }
        boolean shouldRetain =
                activeAbortAlgorithmCount != 0 || hasActiveEventListener(ABORT_EVENT_TYPE);
        if (shouldRetain == retainedBySources) {
            return;
        }
        retainedBySources = shouldRetain;
        for (AbortSignal source : sourceSignals) {
            if (shouldRetain) {
                source.promoteDependent(this);
            } else {
                source.demoteDependent(this);
            }
        }
    }

    private void detachFromSources() {
        AbortSignal[] sources = sourceSignals;
        sourceSignals = null;
        retainedBySources = false;
        if (sources != null) {
            for (AbortSignal source : sources) {
                source.removeDependent(this);
            }
        }
    }

    private void cancelTimeoutIfNeeded() {
        double handle = timeoutHandle;
        timeoutHandle = 0.0;
        if (handle != 0.0) {
            getRuntime().clearTimeout(handle);
        }
    }

    private void initializeAbortedNumber(double reason) {
        setReason(JsPromise.PAYLOAD_NUMBER, reason, false, null);
    }

    private void initializeAbortedBoolean(boolean reason) {
        setReason(JsPromise.PAYLOAD_BOOLEAN, 0.0, reason, null);
    }

    private void initializeAbortedReference(Object reason) {
        setReason(JsPromise.PAYLOAD_REFERENCE, 0.0, false, reason);
    }

    private void initializeAbortedFrom(AbortSignal source) {
        copyReasonFrom(source);
    }

    private void copyReasonFrom(AbortSignal source) {
        setReason(
                source.reasonKind,
                source.reasonNumber,
                source.reasonBoolean,
                source.reasonReference);
    }

    private void setReason(int kind, double number, boolean bool, Object reference) {
        aborted = true;
        reasonKind = kind;
        reasonNumber = number;
        reasonBoolean = bool;
        reasonReference = reference;
    }

    private void requireReasonKind(int expected) {
        if (!aborted) {
            throw new IllegalStateException("AbortSignal is not aborted");
        }
        if (reasonKind != expected) {
            throw new IllegalStateException(
                    "Abort reason kind " + reasonKind + " is not " + expected);
        }
    }

    private static AbortSignal newSignal(
            RuntimeInstance runtime,
            EventExceptionReporter reporter) {
        RuntimeInstance checkedRuntime = Objects.requireNonNull(runtime, "runtime");
        EventExceptionReporter checkedReporter =
                Objects.requireNonNull(reporter, "reporter");
        if (!checkedRuntime.isOwnerThread()) {
            throw new IllegalStateException("AbortSignal created outside its runtime owner thread");
        }
        if (checkedRuntime.isClosed()) {
            throw new IllegalStateException("AbortSignal runtime is closed");
        }
        if (!checkedRuntime.isInsideHostTurn() && !checkedRuntime.isDrainingMicrotasks()) {
            throw new IllegalStateException(
                    "AbortSignal creation requires an active host turn or microtask");
        }
        return new AbortSignal(checkedRuntime, checkedReporter);
    }

    private static void addIdentityOnce(ArrayList<AbortSignal> list, AbortSignal value) {
        if (!containsIdentity(list, value)) {
            list.add(value);
        }
    }

    private static boolean containsIdentity(ArrayList<AbortSignal> list, AbortSignal value) {
        for (AbortSignal candidate : list) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

}
