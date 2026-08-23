package io.github.akisarou.jvmwww.runtime;

import java.util.Objects;

/**
 * Base class for a compiler-generated direct-JVM async-function state machine.
 *
 * <p>One instance is simultaneously the async invocation's result Promise, its live-across-await
 * frame, and the microtask queued to resume it. Generated subclasses store only reached captures
 * and locals that survive an {@code await}; non-surviving values remain ordinary JVM locals.</p>
 *
 * <p>The compiler calls {@link #start()} inside the caller's current language turn. A generated
 * state must settle this Promise, request result adoption with {@link #adoptResult(JsPromise)}, or
 * request one await with {@link #suspendOn(JsPromise, int)}, then return. Await/adoption
 * subscriptions are committed only after the state method returns normally, so an exception
 * cannot leave a rejected frame subscribed to another Promise.</p>
 */
public abstract class AsyncFrame extends JsPromise implements JsPromise.PromiseJob {
    public static final int INITIAL_STATE = 0;

    private static final int LIFECYCLE_NEW = 0;
    private static final int LIFECYCLE_RUNNING = 1;
    private static final int LIFECYCLE_SUSPENDED_AWAIT = 2;
    private static final int LIFECYCLE_SUSPENDED_ADOPTION = 3;
    private static final int LIFECYCLE_FINISHED = 4;

    private static final int REQUEST_NONE = 0;
    private static final int REQUEST_AWAIT = 1;
    private static final int REQUEST_ADOPT = 2;

    private int lifecycle = LIFECYCLE_NEW;
    private int requestKind = REQUEST_NONE;
    private int requestedResumeState;
    private JsPromise requestedSource;
    private JsPromise suspendedSource;
    private JsPromise.PromiseJob nextPromiseJob;

    protected AsyncFrame(RuntimeInstance runtime) {
        super(runtime);
    }

    /**
     * Runs the eager synchronous prefix and returns the same object as the result Promise.
     *
     * <p>A source-level throw is captured and rejects this Promise. It never escapes synchronously
     * to the caller, except for fatal VM/linkage failures or misuse outside a language turn.</p>
     */
    public final AsyncFrame start() {
        RuntimeInstance runtime = getRuntime();
        runtime.assertLanguageExecution();
        if (lifecycle != LIFECYCLE_NEW) {
            throw new IllegalStateException("AsyncFrame.start() may be called exactly once");
        }
        runState(INITIAL_STATE, null);
        return this;
    }

    /**
     * Requests suspension on {@code source}; generated code must return immediately afterward.
     *
     * <p>The subscription is installed only after {@link #executeState(int, JsPromise)} returns
     * normally. Awaiting an already-settled Promise therefore still queues this frame as a
     * microtask, while an exception raised before the return cannot produce a stale subscription.</p>
     */
    protected final void suspendOn(JsPromise source, int nextState) {
        prepareRequest(REQUEST_AWAIT, source);
        requestedResumeState = nextState;
    }

    /**
     * Requests Promise adoption for a source-level {@code return promise}.
     *
     * <p>The frame itself becomes the adoption job. This avoids the ordinary
     * {@link JsPromise#resolveWith(JsPromise)} wrapper allocation while preserving the same
     * first-resolution-wins and asynchronous-adoption semantics.</p>
     */
    protected final void adoptResult(JsPromise source) {
        prepareRequest(REQUEST_ADOPT, source);
    }

    /** Runs one generated state. State zero receives {@code awaited == null}. */
    protected abstract void executeState(int state, JsPromise awaited) throws Throwable;

    /** Verifies a fulfilled void await or throws the exact rejection reason. */
    protected final void awaitVoid(JsPromise awaited) {
        requireAwaitedFulfillment(awaited, PAYLOAD_VOID);
    }

    /** Returns an unboxed awaited number or throws the exact rejection reason. */
    protected final double awaitNumber(JsPromise awaited) {
        requireAwaitedFulfillment(awaited, PAYLOAD_NUMBER);
        return awaited.getNumberPayload();
    }

    /** Returns an unboxed awaited boolean or throws the exact rejection reason. */
    protected final boolean awaitBoolean(JsPromise awaited) {
        requireAwaitedFulfillment(awaited, PAYLOAD_BOOLEAN);
        return awaited.getBooleanPayload();
    }

    /** Returns an awaited reference or throws the exact rejection reason. */
    protected final Object awaitReference(JsPromise awaited) {
        requireAwaitedFulfillment(awaited, PAYLOAD_REFERENCE);
        return awaited.getReferencePayload();
    }

    @Override
    public final void execute(RuntimeInstance runtime) {
        if (runtime != getRuntime()) {
            throw new IllegalArgumentException("AsyncFrame resumed by the wrong RuntimeInstance");
        }
        runtime.assertLanguageExecution();
        JsPromise source = suspendedSource;
        if (source == null) {
            throw new IllegalStateException("AsyncFrame resumed without one active suspension");
        }
        suspendedSource = null;

        if (lifecycle == LIFECYCLE_SUSPENDED_ADOPTION) {
            lifecycle = LIFECYCLE_FINISHED;
            completeContinuationAdoption(source);
            return;
        }
        if (lifecycle != LIFECYCLE_SUSPENDED_AWAIT) {
            throw new IllegalStateException("AsyncFrame resumed in an invalid lifecycle state");
        }
        runState(requestedResumeState, source);
    }

    @Override
    public final JsPromise.PromiseJob getNextPromiseJob() {
        return nextPromiseJob;
    }

    @Override
    public final void setNextPromiseJob(JsPromise.PromiseJob next) {
        nextPromiseJob = next;
    }

    private void prepareRequest(int kind, JsPromise source) {
        RuntimeInstance runtime = getRuntime();
        runtime.assertLanguageExecution();
        if (lifecycle != LIFECYCLE_RUNNING) {
            throw new IllegalStateException(
                    "AsyncFrame can request suspension only while executing a state");
        }
        if (requestKind != REQUEST_NONE) {
            throw new IllegalStateException("AsyncFrame state requested more than one suspension");
        }
        Objects.requireNonNull(source, "source");
        if (source.getRuntime() != runtime) {
            throw new IllegalArgumentException(
                    "AsyncFrame cannot use a Promise from another RuntimeInstance");
        }
        requestKind = kind;
        requestedSource = source;
    }

    private void runState(int state, JsPromise awaited) {
        lifecycle = LIFECYCLE_RUNNING;
        requestKind = REQUEST_NONE;
        requestedSource = null;

        try {
            executeState(state, awaited);
        } catch (JsThrownValue reason) {
            clearRequest();
            lifecycle = LIFECYCLE_FINISHED;
            rejectThrown(reason);
            return;
        } catch (Throwable error) {
            clearRequest();
            lifecycle = LIFECYCLE_FINISHED;
            RuntimeInstance.rethrowIfFatal(error);
            rejectReference(error);
            return;
        }

        if (requestKind == REQUEST_AWAIT) {
            if (!isPending() || isResolutionLocked()) {
                failInvalidState("AsyncFrame cannot await after resolving its result");
                return;
            }
            JsPromise source = requestedSource;
            clearRequest();
            suspendedSource = source;
            lifecycle = LIFECYCLE_SUSPENDED_AWAIT;
            source.subscribeAwaiter(this);
            return;
        }

        if (requestKind == REQUEST_ADOPT) {
            if (!isPending() || isResolutionLocked()) {
                failInvalidState("AsyncFrame cannot adopt after resolving its result");
                return;
            }
            JsPromise source = requestedSource;
            clearRequest();
            suspendedSource = source;
            lifecycle = LIFECYCLE_SUSPENDED_ADOPTION;
            if (!beginContinuationAdoption(source, this)) {
                suspendedSource = null;
                lifecycle = LIFECYCLE_FINISHED;
            } else if (!isPending()) {
                // Self-resolution rejects synchronously and does not subscribe this job.
                suspendedSource = null;
                lifecycle = LIFECYCLE_FINISHED;
            }
            return;
        }

        lifecycle = LIFECYCLE_FINISHED;
        if (isPending() && !isResolutionLocked()) {
            rejectReference(
                    new IllegalStateException(
                            "AsyncFrame state returned without suspending or resolving its result"));
        }
    }

    private void failInvalidState(String message) {
        clearRequest();
        lifecycle = LIFECYCLE_FINISHED;
        if (!isResolutionLocked()) {
            rejectReference(new IllegalStateException(message));
        } else {
            throw new IllegalStateException(message);
        }
    }

    private void clearRequest() {
        requestKind = REQUEST_NONE;
        requestedSource = null;
    }

    private void requireAwaitedFulfillment(JsPromise awaited, int expectedPayloadKind) {
        Objects.requireNonNull(awaited, "awaited");
        if (awaited.getRuntime() != getRuntime()) {
            throw new IllegalArgumentException(
                    "AsyncFrame received an awaited Promise from another RuntimeInstance");
        }
        if (awaited.isRejected()) {
            throw awaited.toThrownValue();
        }
        if (!awaited.isFulfilled()) {
            throw new IllegalStateException("AsyncFrame resumed from a pending Promise");
        }
        if (awaited.getPayloadKind() != expectedPayloadKind) {
            throw new IllegalStateException(
                    "Awaited payload kind "
                            + awaited.getPayloadKind()
                            + " is not "
                            + expectedPayloadKind);
        }
    }
}
