package io.github.akisarou.jvmwww.runtime;

import java.util.Objects;

/**
 * A pending language Promise that is also its foreign completion token and admitted host task.
 *
 * <p>A capability provider creates this object on the runtime owner during an active language turn,
 * returns it as the operation's {@link JsPromise}, and retains the same object in the platform
 * callback. Any thread may call one of the {@code tryFulfill*} or {@code tryReject*} methods. The
 * first call records a transport-safe payload and admits this object to its runtime; no foreign
 * thread settles the language Promise or executes TypeScript directly.</p>
 *
 * <p>Reference overloads with a {@link PlatformReferenceDisposer} take ownership of the supplied
 * reference regardless of their boolean result. The disposer runs exactly once when that
 * completion loses, the runtime closes before delivery, owner-side settlement already won, or
 * owner wake publication fails. Successful Promise settlement transfers ownership to the Promise
 * and does not invoke the disposer.</p>
 */
public final class PlatformPromise extends JsPromise implements RuntimeTask {
    private static final int COMPLETION_OPEN = 0;
    private static final int COMPLETION_QUEUED = 1;
    private static final int COMPLETION_DELIVERING = 2;
    private static final int COMPLETION_FINISHED = 3;
    private static final int COMPLETION_DISCARDED = 4;

    /**
     * Guarded by this object's monitor.
     *
     * <p>The operation completes once, so the JVM monitor fast path avoids allocating one
     * {@code AtomicInteger} per platform Promise and remains safe under R8 field renaming.</p>
     */
    private int completionState = COMPLETION_OPEN;

    /** Foreign-published payload, also guarded by this object's monitor. */
    private int completedState;
    private int completedPayloadKind;
    private double completedNumber;
    private boolean completedBoolean;
    private Object completedReference;
    private PlatformReferenceDisposer referenceDisposer;

    PlatformPromise(RuntimeInstance runtime) {
        super(runtime);
    }

    /** Allocates a platform-backed Promise during one active owner language turn. */
    public static PlatformPromise create(RuntimeInstance runtime) {
        RuntimeInstance checked = Objects.requireNonNull(runtime, "runtime");
        checked.assertLanguageExecution();
        return new PlatformPromise(checked);
    }

    /** Publishes a fulfilled-void completion. The first platform completion wins. */
    public boolean tryFulfillVoid() {
        return tryPublish(
                STATE_FULFILLED,
                PAYLOAD_VOID,
                0.0,
                false,
                null,
                null);
    }

    /** Publishes a fulfilled number without boxing it. The first platform completion wins. */
    public boolean tryFulfillNumber(double value) {
        return tryPublish(
                STATE_FULFILLED,
                PAYLOAD_NUMBER,
                value,
                false,
                null,
                null);
    }

    /** Publishes a fulfilled boolean without boxing it. The first platform completion wins. */
    public boolean tryFulfillBoolean(boolean value) {
        return tryPublish(
                STATE_FULFILLED,
                PAYLOAD_BOOLEAN,
                0.0,
                value,
                null,
                null);
    }

    /** Publishes a fulfilled Java reference whose ordinary GC lifetime needs no explicit drop. */
    public boolean tryFulfillReference(Object value) {
        return tryPublish(
                STATE_FULFILLED,
                PAYLOAD_REFERENCE,
                0.0,
                false,
                value,
                null);
    }

    /**
     * Publishes a fulfilled retained reference.
     *
     * <p>Ownership moves into this method even when it loses the first-completion race.</p>
     */
    public boolean tryFulfillReference(
            Object value,
            PlatformReferenceDisposer disposer) {
        return tryPublish(
                STATE_FULFILLED,
                PAYLOAD_REFERENCE,
                0.0,
                false,
                value,
                Objects.requireNonNull(disposer, "disposer"));
    }

    /** Publishes a rejected-void completion. The first platform completion wins. */
    public boolean tryRejectVoid() {
        return tryPublish(
                STATE_REJECTED,
                PAYLOAD_VOID,
                0.0,
                false,
                null,
                null);
    }

    /** Publishes an unboxed numeric rejection reason. */
    public boolean tryRejectNumber(double reason) {
        return tryPublish(
                STATE_REJECTED,
                PAYLOAD_NUMBER,
                reason,
                false,
                null,
                null);
    }

    /** Publishes an unboxed boolean rejection reason. */
    public boolean tryRejectBoolean(boolean reason) {
        return tryPublish(
                STATE_REJECTED,
                PAYLOAD_BOOLEAN,
                0.0,
                reason,
                null,
                null);
    }

    /** Publishes a Java reference rejection reason with ordinary GC ownership. */
    public boolean tryRejectReference(Object reason) {
        return tryPublish(
                STATE_REJECTED,
                PAYLOAD_REFERENCE,
                0.0,
                false,
                reason,
                null);
    }

    /**
     * Publishes a retained Java reference rejection reason.
     *
     * <p>Ownership moves into this method even when it loses the first-completion race.</p>
     */
    public boolean tryRejectReference(
            Object reason,
            PlatformReferenceDisposer disposer) {
        return tryPublish(
                STATE_REJECTED,
                PAYLOAD_REFERENCE,
                0.0,
                false,
                reason,
                Objects.requireNonNull(disposer, "disposer"));
    }

    /**
     * Returns whether this invocation won the platform token's first-completion race.
     *
     * <p>A {@code true} result does not promise delivery when the owning runtime is already closed.
     * Reference ownership has moved in either result; a losing or discarded reference is disposed
     * before this call returns or during runtime shutdown.</p>
     */
    private boolean tryPublish(
            int state,
            int payloadKind,
            double number,
            boolean bool,
            Object reference,
            PlatformReferenceDisposer disposer) {
        boolean won;
        synchronized (this) {
            won = completionState == COMPLETION_OPEN;
            if (won) {
                completedState = state;
                completedPayloadKind = payloadKind;
                completedNumber = number;
                completedBoolean = bool;
                completedReference = reference;
                referenceDisposer = disposer;
                completionState = COMPLETION_QUEUED;
            }
        }

        if (!won) {
            disposeReference(reference, disposer);
            return false;
        }

        getRuntime().admitHostTask(this);
        return true;
    }

    /** Settles the language Promise on its owner as one ordinary host task. */
    @Override
    public void execute(RuntimeInstance runtime) {
        if (runtime != getRuntime()) {
            throw new IllegalArgumentException(
                    "PlatformPromise delivered by the wrong RuntimeInstance");
        }

        final int state;
        final int payloadKind;
        final double number;
        final boolean bool;
        final Object reference;
        final PlatformReferenceDisposer disposer;
        synchronized (this) {
            if (completionState != COMPLETION_QUEUED) {
                throw new IllegalStateException(
                        "PlatformPromise delivered in completion state " + completionState);
            }
            completionState = COMPLETION_DELIVERING;
            state = completedState;
            payloadKind = completedPayloadKind;
            number = completedNumber;
            bool = completedBoolean;
            reference = completedReference;
            disposer = referenceDisposer;
        }

        boolean ownershipTransferred = false;
        try {
            ownershipTransferred =
                    settleCapturedCompletion(state, payloadKind, number, bool, reference);
        } finally {
            synchronized (this) {
                clearCapturedCompletion();
                completionState = COMPLETION_FINISHED;
            }
            if (!ownershipTransferred) {
                disposeReference(reference, disposer);
            }
        }
    }

    /**
     * Releases a retained completion removed before owner delivery.
     *
     * <p>This can run on a foreign producer thread when admission races runtime shutdown.</p>
     */
    @Override
    public void discard() {
        Object reference;
        PlatformReferenceDisposer disposer;
        synchronized (this) {
            if (completionState != COMPLETION_QUEUED) {
                return;
            }
            completionState = COMPLETION_DISCARDED;
            reference = completedReference;
            disposer = referenceDisposer;
            clearCapturedCompletion();
        }
        disposeReference(reference, disposer);
    }

    private boolean settleCapturedCompletion(
            int state,
            int payloadKind,
            double number,
            boolean bool,
            Object reference) {
        if (state == STATE_FULFILLED) {
            switch (payloadKind) {
                case PAYLOAD_VOID:
                    return fulfillVoid();
                case PAYLOAD_NUMBER:
                    return fulfillNumber(number);
                case PAYLOAD_BOOLEAN:
                    return fulfillBoolean(bool);
                case PAYLOAD_REFERENCE:
                    return fulfillReference(reference);
                default:
                    throw new AssertionError(
                            "Unknown platform fulfillment payload kind: " + payloadKind);
            }
        }

        if (state == STATE_REJECTED) {
            switch (payloadKind) {
                case PAYLOAD_VOID:
                    return rejectVoid();
                case PAYLOAD_NUMBER:
                    return rejectNumber(number);
                case PAYLOAD_BOOLEAN:
                    return rejectBoolean(bool);
                case PAYLOAD_REFERENCE:
                    return rejectReference(reference);
                default:
                    throw new AssertionError(
                            "Unknown platform rejection payload kind: " + payloadKind);
            }
        }

        throw new AssertionError("Unknown platform Promise completion state: " + state);
    }

    private void clearCapturedCompletion() {
        completedState = STATE_PENDING;
        completedPayloadKind = PAYLOAD_VOID;
        completedNumber = 0.0;
        completedBoolean = false;
        completedReference = null;
        referenceDisposer = null;
    }

    private static void disposeReference(
            Object reference,
            PlatformReferenceDisposer disposer) {
        if (disposer != null) {
            disposer.dispose(reference);
        }
    }
}
