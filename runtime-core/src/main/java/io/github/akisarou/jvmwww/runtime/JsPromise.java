package io.github.akisarou.jvmwww.runtime;

import java.util.Objects;

/**
 * Owner-confined Promise state used by generated direct-JVM TypeScript.
 *
 * <p>The object stores primitive payloads without Java boxing. State transitions and reaction
 * registration are legal only while generated language code is running on the owning
 * {@link RuntimeInstance}. A platform completion must first enter through
 * {@link RuntimeInstance#admitHostTask(RuntimeTask)} and settle the Promise from that host turn.</p>
 *
 * <p>This static-profile implementation adopts another {@code JsPromise}. Arbitrary dynamic
 * thenable assimilation is intentionally absent until checked IR admits that shape.</p>
 */
public class JsPromise {
    public static final int STATE_PENDING = 0;
    public static final int STATE_FULFILLED = 1;
    public static final int STATE_REJECTED = 2;

    public static final int PAYLOAD_VOID = 0;
    public static final int PAYLOAD_NUMBER = 1;
    public static final int PAYLOAD_BOOLEAN = 2;
    public static final int PAYLOAD_REFERENCE = 3;

    /**
     * Intrusive Promise job contract shared by generic reactions and generated async frames.
     *
     * <p>The link belongs to the job object itself, so a generated frame can be inserted directly
     * into a Promise's FIFO reaction list without allocating a wrapper node or {@link Runnable}.</p>
     */
    interface PromiseJob extends RuntimeTask {
        PromiseJob getNextPromiseJob();
        void setNextPromiseJob(PromiseJob next);
    }

    private final RuntimeInstance runtime;

    private int state = STATE_PENDING;
    private int payloadKind = PAYLOAD_VOID;
    private double numberPayload;
    private boolean booleanPayload;
    private Object referencePayload;

    /** Resolve/reject functions are once-only even while following a pending Promise. */
    private boolean resolutionLocked;

    private PromiseJob reactionsHead;
    private PromiseJob reactionsTail;

    private boolean handled;
    private boolean rejectionQueued;
    private boolean reportedUnhandled;
    private boolean reportedHandled;

    protected JsPromise(RuntimeInstance runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public final RuntimeInstance getRuntime() {
        return runtime;
    }

    public final int getState() {
        runtime.assertOwnerAccess();
        return state;
    }

    public final boolean isPending() {
        runtime.assertOwnerAccess();
        return state == STATE_PENDING;
    }

    public final boolean isFulfilled() {
        runtime.assertOwnerAccess();
        return state == STATE_FULFILLED;
    }

    public final boolean isRejected() {
        runtime.assertOwnerAccess();
        return state == STATE_REJECTED;
    }

    public final int getPayloadKind() {
        runtime.assertOwnerAccess();
        requireSettled();
        return payloadKind;
    }

    public final double getNumberPayload() {
        runtime.assertOwnerAccess();
        requirePayloadKind(PAYLOAD_NUMBER);
        return numberPayload;
    }

    public final boolean getBooleanPayload() {
        runtime.assertOwnerAccess();
        requirePayloadKind(PAYLOAD_BOOLEAN);
        return booleanPayload;
    }

    public final Object getReferencePayload() {
        runtime.assertOwnerAccess();
        requirePayloadKind(PAYLOAD_REFERENCE);
        return referencePayload;
    }

    /** Converts a rejected Promise's exact reason into the JVM throw carrier used by await. */
    public final JsThrownValue toThrownValue() {
        runtime.assertLanguageExecution();
        if (state != STATE_REJECTED) {
            throw new IllegalStateException("Only a rejected Promise has a throw reason");
        }
        switch (payloadKind) {
            case PAYLOAD_VOID:
                return JsThrownValue.voidValue();
            case PAYLOAD_NUMBER:
                return JsThrownValue.number(numberPayload);
            case PAYLOAD_BOOLEAN:
                return JsThrownValue.bool(booleanPayload);
            case PAYLOAD_REFERENCE:
                return JsThrownValue.reference(referencePayload);
            default:
                throw new AssertionError("Unknown Promise payload kind: " + payloadKind);
        }
    }

    /**
     * Attaches reactions and returns their destination Promise.
     *
     * <p>Both handlers may be {@code null}; a missing handler propagates the corresponding
     * settlement. Registration always marks this Promise handled, matching ECMAScript's default
     * thrower reaction for an omitted rejection handler.</p>
     */
    public final JsPromise then(PromiseReaction onFulfilled, PromiseReaction onRejected) {
        runtime.assertLanguageExecution();
        markHandled();

        JsPromise destination = new JsPromise(runtime);
        subscribe(new ReactionJob(this, destination, onFulfilled, onRejected));
        return destination;
    }

    public final JsPromise catchRejected(PromiseReaction onRejected) {
        return then(null, Objects.requireNonNull(onRejected, "onRejected"));
    }

    public final boolean fulfillVoid() {
        runtime.assertLanguageExecution();
        if (!lockResolution()) {
            return false;
        }
        complete(STATE_FULFILLED, PAYLOAD_VOID, 0.0, false, null);
        return true;
    }

    public final boolean fulfillNumber(double value) {
        runtime.assertLanguageExecution();
        if (!lockResolution()) {
            return false;
        }
        complete(STATE_FULFILLED, PAYLOAD_NUMBER, value, false, null);
        return true;
    }

    public final boolean fulfillBoolean(boolean value) {
        runtime.assertLanguageExecution();
        if (!lockResolution()) {
            return false;
        }
        complete(STATE_FULFILLED, PAYLOAD_BOOLEAN, 0.0, value, null);
        return true;
    }

    public final boolean fulfillReference(Object value) {
        runtime.assertLanguageExecution();
        if (!lockResolution()) {
            return false;
        }
        complete(STATE_FULFILLED, PAYLOAD_REFERENCE, 0.0, false, value);
        return true;
    }

    public final boolean rejectVoid() {
        runtime.assertLanguageExecution();
        if (!lockResolution()) {
            return false;
        }
        complete(STATE_REJECTED, PAYLOAD_VOID, 0.0, false, null);
        return true;
    }

    public final boolean rejectNumber(double reason) {
        runtime.assertLanguageExecution();
        if (!lockResolution()) {
            return false;
        }
        complete(STATE_REJECTED, PAYLOAD_NUMBER, reason, false, null);
        return true;
    }

    public final boolean rejectBoolean(boolean reason) {
        runtime.assertLanguageExecution();
        if (!lockResolution()) {
            return false;
        }
        complete(STATE_REJECTED, PAYLOAD_BOOLEAN, 0.0, reason, null);
        return true;
    }

    public final boolean rejectReference(Object reason) {
        runtime.assertLanguageExecution();
        if (!lockResolution()) {
            return false;
        }
        complete(STATE_REJECTED, PAYLOAD_REFERENCE, 0.0, false, reason);
        return true;
    }

    /** Rejects with the exact payload carried by generated Java exception flow. */
    public final boolean rejectThrown(JsThrownValue reason) {
        runtime.assertLanguageExecution();
        Objects.requireNonNull(reason, "reason");
        if (!lockResolution()) {
            return false;
        }
        completeFromThrown(reason);
        return true;
    }

    /**
     * Resolves this Promise by adopting another Promise from the same runtime.
     *
     * <p>The adoption reaction is always a microtask, including when {@code source} is already
     * settled. Calling this method consumes this Promise's one resolve attempt immediately, so a
     * later direct fulfill/reject cannot beat a still-pending adopted Promise.</p>
     */
    public final boolean resolveWith(JsPromise source) {
        runtime.assertLanguageExecution();
        Objects.requireNonNull(source, "source");
        validateSameRuntime(source);
        if (!lockResolution()) {
            return false;
        }
        if (source == this) {
            complete(
                    STATE_REJECTED,
                    PAYLOAD_REFERENCE,
                    0.0,
                    false,
                    new JsTypeError("Chaining cycle detected for Promise"));
            return true;
        }

        source.markHandled();
        source.subscribe(new AdoptionJob(source, this));
        return true;
    }

    /**
     * Registers a generated async frame as the exact await reaction job.
     *
     * <p>Await observes rejection, so the source is marked handled at registration time. The job
     * itself is linked into the same FIFO as ordinary {@code then} reactions.</p>
     */
    final void subscribeAwaiter(PromiseJob job) {
        runtime.assertLanguageExecution();
        markHandled();
        subscribe(Objects.requireNonNull(job, "job"));
    }

    /**
     * Locks this Promise and adopts {@code source} using a caller-owned intrusive job.
     *
     * <p>This is the no-wrapper path used when an async frame is also its result Promise and the
     * job that completes adoption. A {@code true} result means the resolve attempt was accepted;
     * self-resolution may already have rejected the destination instead of subscribing the job.</p>
     */
    final boolean beginContinuationAdoption(JsPromise source, PromiseJob job) {
        runtime.assertLanguageExecution();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(job, "job");
        validateSameRuntime(source);
        if (!lockResolution()) {
            return false;
        }
        if (source == this) {
            complete(
                    STATE_REJECTED,
                    PAYLOAD_REFERENCE,
                    0.0,
                    false,
                    new JsTypeError("Chaining cycle detected for Promise"));
            return true;
        }

        source.markHandled();
        source.subscribe(job);
        return true;
    }

    final void completeContinuationAdoption(JsPromise source) {
        runtime.assertLanguageExecution();
        completeAdoption(source);
    }

    final boolean isResolutionLocked() {
        runtime.assertOwnerAccess();
        return resolutionLocked;
    }

    boolean isRejectionQueued() {
        return rejectionQueued;
    }

    void setRejectionQueued(boolean value) {
        rejectionQueued = value;
    }

    boolean shouldReportUnhandled() {
        return state == STATE_REJECTED && !handled && !reportedUnhandled;
    }

    void markReportedUnhandled() {
        reportedUnhandled = true;
    }

    private void validateSameRuntime(JsPromise source) {
        if (source.runtime != runtime) {
            throw new IllegalArgumentException("Cannot use a Promise from another RuntimeInstance");
        }
    }

    private void markHandled() {
        if (handled) {
            return;
        }
        handled = true;
        if (reportedUnhandled && !reportedHandled) {
            reportedHandled = true;
            runtime.notifyPromiseHandled(this);
        }
    }

    private boolean lockResolution() {
        if (resolutionLocked) {
            return false;
        }
        resolutionLocked = true;
        return true;
    }

    private void subscribe(PromiseJob job) {
        job.setNextPromiseJob(null);
        if (state == STATE_PENDING) {
            if (reactionsTail == null) {
                reactionsHead = job;
            } else {
                reactionsTail.setNextPromiseJob(job);
            }
            reactionsTail = job;
        } else {
            runtime.queueMicrotask(job);
        }
    }

    private void resolveFromSettled(JsPromise source) {
        if (!lockResolution()) {
            return;
        }
        copySettledSource(source);
    }

    private void completeAdoption(JsPromise source) {
        if (!resolutionLocked || state != STATE_PENDING) {
            return;
        }
        copySettledSource(source);
    }

    private void copySettledSource(JsPromise source) {
        if (source.state == STATE_PENDING) {
            throw new IllegalStateException("Cannot copy a pending Promise settlement");
        }
        complete(
                source.state,
                source.payloadKind,
                source.numberPayload,
                source.booleanPayload,
                source.referencePayload);
    }

    private void completeFromThrown(JsThrownValue reason) {
        switch (reason.getPayloadKind()) {
            case PAYLOAD_VOID:
                complete(STATE_REJECTED, PAYLOAD_VOID, 0.0, false, null);
                return;
            case PAYLOAD_NUMBER:
                complete(
                        STATE_REJECTED,
                        PAYLOAD_NUMBER,
                        reason.getNumberPayload(),
                        false,
                        null);
                return;
            case PAYLOAD_BOOLEAN:
                complete(
                        STATE_REJECTED,
                        PAYLOAD_BOOLEAN,
                        0.0,
                        reason.getBooleanPayload(),
                        null);
                return;
            case PAYLOAD_REFERENCE:
                complete(
                        STATE_REJECTED,
                        PAYLOAD_REFERENCE,
                        0.0,
                        false,
                        reason.getReferencePayload());
                return;
            default:
                throw new AssertionError("Unknown thrown payload kind: " + reason.getPayloadKind());
        }
    }

    private void complete(
            int completedState,
            int completedPayloadKind,
            double completedNumber,
            boolean completedBoolean,
            Object completedReference) {
        if (state != STATE_PENDING) {
            throw new IllegalStateException("Promise completion attempted after settlement");
        }
        state = completedState;
        payloadKind = completedPayloadKind;
        numberPayload = completedNumber;
        booleanPayload = completedBoolean;
        referencePayload = completedReference;

        if (state == STATE_REJECTED && !handled) {
            runtime.notePromiseRejected(this);
        }

        PromiseJob job = reactionsHead;
        reactionsHead = null;
        reactionsTail = null;
        while (job != null) {
            PromiseJob next = job.getNextPromiseJob();
            job.setNextPromiseJob(null);
            runtime.queueMicrotask(job);
            job = next;
        }
    }

    private void requireSettled() {
        if (state == STATE_PENDING) {
            throw new IllegalStateException("Promise is still pending");
        }
    }

    private void requirePayloadKind(int expected) {
        requireSettled();
        if (payloadKind != expected) {
            throw new IllegalStateException(
                    "Promise payload kind " + payloadKind + " is not " + expected);
        }
    }

    private abstract static class LinkedPromiseJob implements PromiseJob {
        private PromiseJob next;

        @Override
        public final PromiseJob getNextPromiseJob() {
            return next;
        }

        @Override
        public final void setNextPromiseJob(PromiseJob next) {
            this.next = next;
        }
    }

    private static final class AdoptionJob extends LinkedPromiseJob {
        private final JsPromise source;
        private final JsPromise destination;

        AdoptionJob(JsPromise source, JsPromise destination) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public void execute(RuntimeInstance runtime) {
            destination.completeAdoption(source);
        }
    }

    private static final class ReactionJob extends LinkedPromiseJob {
        private final JsPromise source;
        private final JsPromise destination;
        private final PromiseReaction onFulfilled;
        private final PromiseReaction onRejected;

        ReactionJob(
                JsPromise source,
                JsPromise destination,
                PromiseReaction onFulfilled,
                PromiseReaction onRejected) {
            this.source = source;
            this.destination = destination;
            this.onFulfilled = onFulfilled;
            this.onRejected = onRejected;
        }

        @Override
        public void execute(RuntimeInstance runtime) {
            PromiseReaction handler =
                    source.state == STATE_FULFILLED ? onFulfilled : onRejected;
            if (handler == null) {
                destination.resolveFromSettled(source);
                return;
            }

            try {
                handler.execute(runtime, source, destination);
                if (!destination.resolutionLocked) {
                    destination.fulfillVoid();
                }
            } catch (JsThrownValue reason) {
                destination.rejectThrown(reason);
            } catch (Throwable error) {
                RuntimeInstance.rethrowIfFatal(error);
                destination.rejectReference(error);
            }
        }
    }
}
