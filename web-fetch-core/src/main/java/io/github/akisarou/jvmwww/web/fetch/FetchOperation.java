package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.web.events.AbortAlgorithm;
import io.github.akisarou.jvmwww.web.events.AbortSignal;
import java.util.Objects;

/** One Fetch operation: returned Promise, foreign completion token, abort algorithm, and host task. */
final class FetchOperation extends JsPromise
        implements FetchTransportCallback, AbortAlgorithm, RuntimeTask {
    private static final int OPEN = 0;
    private static final int QUEUED_RESPONSE = 1;
    private static final int QUEUED_FAILURE = 2;
    private static final int QUEUED_ABORT = 3;
    private static final int DELIVERING = 4;
    private static final int FINISHED = 5;
    private static final int DISCARDED = 6;

    private final RuntimeInstance runtime;
    private final AbortSignal signal;
    private int completionState = OPEN;
    private FetchTransportResponse response;
    private Throwable failure;
    private int abortReasonKind;
    private double abortReasonNumber;
    private boolean abortReasonBoolean;
    private Object abortReasonReference;
    private FetchTransportCall call;
    private boolean abortRegistered;

    FetchOperation(RuntimeInstance runtime, AbortSignal signal) {
        super(runtime);
        this.runtime = runtime;
        this.signal = signal;
    }

    void start(FetchTransport transport, FetchTransportRequest request) {
        if (signal != null) {
            if (signal.isAborted()) {
                queueAbortFromSignal();
                return;
            }
            abortRegistered = signal.addAbortAlgorithm(this);
            if (!abortRegistered) {
                queueAbortFromSignal();
                return;
            }
        }

        try {
            FetchTransportCall started = Objects.requireNonNull(
                    transport.start(request, this), "FetchTransport.start returned null");
            boolean cancelImmediately;
            synchronized (this) {
                call = started;
                cancelImmediately = completionState == QUEUED_ABORT || completionState == DISCARDED;
            }
            if (cancelImmediately) {
                started.cancel();
            }
        } catch (Throwable error) {
            rethrowIfFatal(error);
            onFailure(error);
        }
    }

    @Override
    public void onResponse(FetchTransportResponse completedResponse) {
        FetchTransportResponse checked = Objects.requireNonNull(completedResponse, "response");
        synchronized (this) {
            if (completionState != OPEN) return;
            response = checked;
            completionState = QUEUED_RESPONSE;
        }
        admitClaimed();
    }

    @Override
    public void onFailure(Throwable error) {
        Throwable checked = Objects.requireNonNull(error, "error");
        synchronized (this) {
            if (completionState != OPEN) return;
            failure = checked;
            completionState = QUEUED_FAILURE;
        }
        admitClaimed();
    }

    @Override
    public void run(AbortSignal abortedSignal) {
        if (abortedSignal != signal) {
            throw new IllegalArgumentException("Fetch abort delivered by another AbortSignal");
        }
        FetchTransportCall localCall;
        synchronized (this) {
            if (completionState != OPEN) return;
            captureAbortReasonLocked(abortedSignal);
            completionState = QUEUED_ABORT;
            localCall = call;
        }
        if (localCall != null) localCall.cancel();
        admitClaimed();
    }

    @Override
    public void execute(RuntimeInstance owner) {
        if (owner != runtime) {
            throw new IllegalArgumentException("Fetch operation delivered by another RuntimeInstance");
        }
        final int state;
        final FetchTransportResponse capturedResponse;
        final Throwable capturedFailure;
        synchronized (this) {
            state = completionState;
            if (state != QUEUED_RESPONSE && state != QUEUED_FAILURE && state != QUEUED_ABORT) {
                throw new IllegalStateException("Fetch delivered in completion state " + state);
            }
            completionState = DELIVERING;
            capturedResponse = response;
            capturedFailure = failure;
            response = null;
            failure = null;
        }

        detachAbort();
        try {
            if (state == QUEUED_RESPONSE) {
                try {
                    fulfillReference(new Response(runtime, capturedResponse));
                } catch (Throwable error) {
                    rethrowIfFatal(error);
                    rejectNetworkError("Network response was invalid", error);
                }
            } else if (state == QUEUED_FAILURE) {
                rejectNetworkError("Network request failed", capturedFailure);
            } else {
                rejectCapturedAbortReason();
            }
        } finally {
            synchronized (this) {
                completionState = FINISHED;
                call = null;
            }
        }
    }

    @Override
    public void discard() {
        FetchTransportCall localCall;
        synchronized (this) {
            if (completionState == FINISHED || completionState == DISCARDED) return;
            completionState = DISCARDED;
            response = null;
            failure = null;
            localCall = call;
            call = null;
        }
        if (localCall != null) localCall.cancel();
    }

    private void admitClaimed() { runtime.admitHostTask(this); }

    private void queueAbortFromSignal() {
        synchronized (this) {
            if (completionState != OPEN) return;
            captureAbortReasonLocked(signal);
            completionState = QUEUED_ABORT;
        }
        admitClaimed();
    }

    private void captureAbortReasonLocked(AbortSignal abortedSignal) {
        int kind = abortedSignal.getReasonKind();
        abortReasonKind = kind;
        if (kind == PAYLOAD_NUMBER) {
            abortReasonNumber = abortedSignal.getReasonNumber();
        } else if (kind == PAYLOAD_BOOLEAN) {
            abortReasonBoolean = abortedSignal.getReasonBoolean();
        } else if (kind == PAYLOAD_REFERENCE) {
            abortReasonReference = abortedSignal.getReasonReference();
        }
    }

    private void rejectCapturedAbortReason() {
        switch (abortReasonKind) {
            case PAYLOAD_VOID: rejectVoid(); break;
            case PAYLOAD_NUMBER: rejectNumber(abortReasonNumber); break;
            case PAYLOAD_BOOLEAN: rejectBoolean(abortReasonBoolean); break;
            case PAYLOAD_REFERENCE: rejectReference(abortReasonReference); break;
            default: throw new AssertionError("Unknown abort reason kind: " + abortReasonKind);
        }
    }

    private void rejectNetworkError(String message, Throwable cause) {
        JsTypeError networkError = new JsTypeError(message);
        if (cause != null) networkError.initCause(cause);
        rejectReference(networkError);
    }

    private void detachAbort() {
        if (signal != null && abortRegistered) {
            signal.removeAbortAlgorithm(this);
            abortRegistered = false;
        }
    }

    private static void rethrowIfFatal(Throwable error) {
        if (error instanceof ThreadDeath) throw (ThreadDeath) error;
        if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
        if (error instanceof LinkageError) throw (LinkageError) error;
    }
}
