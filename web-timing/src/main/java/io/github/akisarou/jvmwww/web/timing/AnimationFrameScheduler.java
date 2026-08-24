package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource;
import java.util.Objects;

/**
 * Owner-confined requestAnimationFrame scheduler over one replaceable host frame callback.
 *
 * <p>Callback registrations live in reusable parallel-array slots. The scheduler itself is the one
 * host callback and the one runtime-owned lifecycle resource; no wrapper, map entry, Runnable, or
 * task is allocated for an individual frame request.</p>
 */
public final class AnimationFrameScheduler
        implements AnimationFrameHost.FrameCallback, RuntimeOwnedResource {
    private static final int INITIAL_CAPACITY = 8;
    private static final int NO_SLOT = -1;
    private static final int HANDLE_INDEX_BITS = 20;
    private static final long HANDLE_INDEX_MASK = (1L << HANDLE_INDEX_BITS) - 1L;
    private static final long MAX_HANDLE_GENERATION =
            (1L << (53 - HANDLE_INDEX_BITS)) - 1L;
    private static final long MAX_SAFE_HANDLE = (1L << 53) - 1L;

    private final RuntimeInstance runtime;
    private final AnimationFrameHost host;
    private final AnimationFrameExceptionReporter exceptionReporter;
    private final Performance performance;

    private FrameRequestCallback[] callbacks = new FrameRequestCallback[INITIAL_CAPACITY];
    private long[] generations = new long[INITIAL_CAPACITY];
    private long[] frameSequences = new long[INITIAL_CAPACITY];
    private int[] nextOrder = new int[INITIAL_CAPACITY];
    private int[] previousOrder = new int[INITIAL_CAPACITY];
    private int[] nextFreeSlot = new int[INITIAL_CAPACITY];
    private boolean[] active = new boolean[INITIAL_CAPACITY];
    private boolean[] cancelledRunning = new boolean[INITIAL_CAPACITY];

    private int slotCount;
    private int freeSlotHead = NO_SLOT;
    private int pendingHead = NO_SLOT;
    private int pendingTail = NO_SLOT;
    private int runningHead = NO_SLOT;
    private int runningTail = NO_SLOT;
    private long nextFrameSequence = 1L;
    private long pendingFrameSequence;
    private long runningFrameSequence;
    private boolean frameRequested;
    private boolean insideFrame;
    private boolean closed;
    private int runtimeResourceSlot = NO_SLOT;
    private double lastFrameTimestampMilliseconds;

    public AnimationFrameScheduler(RuntimeInstance runtime, AnimationFrameHost host) {
        this(runtime, host, DefaultAnimationFrameExceptionReporter.INSTANCE);
    }

    public AnimationFrameScheduler(
            RuntimeInstance runtime,
            AnimationFrameHost host,
            AnimationFrameExceptionReporter exceptionReporter) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        this.host = Objects.requireNonNull(host, "host");
        this.exceptionReporter = Objects.requireNonNull(exceptionReporter, "exceptionReporter");
        this.performance = new Performance(runtime, host);
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public Performance getPerformance() {
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        return performance;
    }

    /** Registers a callback for the next display frame and returns an exact-number handle. */
    public double requestAnimationFrame(FrameRequestCallback callback) {
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        ensureOpen();
        FrameRequestCallback checked = Objects.requireNonNull(callback, "callback");

        boolean allocatedFrameSequence = false;
        if (pendingFrameSequence == 0L) {
            pendingFrameSequence = allocateFrameSequence();
            allocatedFrameSequence = true;
        }

        int slot;
        try {
            slot = allocateSlot(checked, pendingFrameSequence);
        } catch (RuntimeException error) {
            if (allocatedFrameSequence && pendingHead == NO_SLOT) pendingFrameSequence = 0L;
            throw error;
        } catch (Error error) {
            if (allocatedFrameSequence && pendingHead == NO_SLOT) pendingFrameSequence = 0L;
            throw error;
        }
        appendPending(slot);

        try {
            ensureFrameRequested();
        } catch (RuntimeException error) {
            rollbackPendingSlot(slot);
            throw error;
        } catch (Error error) {
            rollbackPendingSlot(slot);
            throw error;
        }
        return (double) encodeHandle(slot);
    }

    /** Cancels a pending or not-yet-invoked callback; invalid and stale handles are no-ops. */
    public void cancelAnimationFrame(double handle) {
        TimingRuntimeChecks.assertLanguageExecution(runtime);
        if (closed) return;

        int slot = lookupHandle(handle);
        if (slot == NO_SLOT) return;

        if (insideFrame && frameSequences[slot] == runningFrameSequence) {
            // Keep the running-list slot unavailable until the iterator reaches it. A callback
            // cancelled by an earlier callback can therefore never be reused and accidentally run
            // in the same frame.
            callbacks[slot] = null;
            cancelledRunning[slot] = true;
            return;
        }

        unlinkPending(slot);
        releaseSlot(slot);
        if (pendingHead == NO_SLOT) {
            pendingFrameSequence = 0L;
            cancelEmptyHostRequest();
        }
        releaseRuntimeOwnershipIfIdle();
    }

    /** One platform frame callback becomes one host turn and one final microtask checkpoint. */
    @Override
    public void onFrame(long frameTimeNanos) {
        if (runtime.isClosed() || closed || !frameRequested) {
            return;
        }
        if (!runtime.isOwnerThread()) {
            throw new IllegalStateException("Animation frame delivered outside the runtime owner");
        }

        frameRequested = false;
        runningHead = pendingHead;
        runningTail = pendingTail;
        runningFrameSequence = pendingFrameSequence;
        pendingHead = NO_SLOT;
        pendingTail = NO_SLOT;
        pendingFrameSequence = 0L;

        if (runningHead == NO_SLOT) {
            runningFrameSequence = 0L;
            releaseRuntimeOwnershipIfIdle();
            return;
        }

        runtime.enterHostTurn();
        insideFrame = true;
        try {
            double timestamp = performance.frameTimestampMilliseconds(frameTimeNanos);
            if (timestamp < lastFrameTimestampMilliseconds) {
                timestamp = lastFrameTimestampMilliseconds;
            } else {
                lastFrameTimestampMilliseconds = timestamp;
            }
            runCallbacks(timestamp);
        } finally {
            discardUnreachedRunningCallbacks();
            insideFrame = false;
            runningFrameSequence = 0L;
            runtime.leaveHostTurn();
            releaseRuntimeOwnershipIfIdle();
        }
    }

    /** Runtime shutdown cancels the one pending host frame and releases all callback references. */
    @Override
    public void closeForRuntime() {
        if (closed) return;
        closed = true;
        runtimeResourceSlot = NO_SLOT;

        Throwable cancellationFailure = null;
        if (frameRequested) {
            frameRequested = false;
            try {
                host.cancelFrame(this);
            } catch (Throwable error) {
                rethrowIfFatal(error);
                cancellationFailure = error;
            }
        }

        for (int slot = 0; slot < slotCount; slot++) {
            callbacks[slot] = null;
            active[slot] = false;
            cancelledRunning[slot] = false;
            frameSequences[slot] = 0L;
            nextOrder[slot] = NO_SLOT;
            previousOrder[slot] = NO_SLOT;
            nextFreeSlot[slot] = NO_SLOT;
        }
        freeSlotHead = NO_SLOT;
        pendingHead = NO_SLOT;
        pendingTail = NO_SLOT;
        runningHead = NO_SLOT;
        runningTail = NO_SLOT;
        pendingFrameSequence = 0L;
        runningFrameSequence = 0L;
        insideFrame = false;

        if (cancellationFailure != null) {
            throwAsUnchecked(cancellationFailure);
        }
    }

    private void runCallbacks(double timestamp) {
        while (runningHead != NO_SLOT) {
            int slot = runningHead;
            int next = nextOrder[slot];
            runningHead = next;
            if (next == NO_SLOT) {
                runningTail = NO_SLOT;
            } else {
                previousOrder[next] = NO_SLOT;
            }
            nextOrder[slot] = NO_SLOT;
            previousOrder[slot] = NO_SLOT;

            FrameRequestCallback callback = callbacks[slot];
            boolean cancelled = cancelledRunning[slot] || callback == null;
            releaseSlot(slot);
            if (cancelled) continue;

            try {
                callback.run(timestamp);
            } catch (Throwable error) {
                rethrowIfFatal(error);
                reportCallbackFailure(error);
            }
        }
    }

    private void reportCallbackFailure(Throwable error) {
        try {
            exceptionReporter.report(runtime, error);
        } catch (Throwable reporterFailure) {
            rethrowIfFatal(reporterFailure);
            if (reporterFailure != error) {
                error.addSuppressed(reporterFailure);
            }
            DefaultAnimationFrameExceptionReporter.INSTANCE.report(runtime, error);
        }
    }

    private void ensureFrameRequested() {
        if (frameRequested || pendingHead == NO_SLOT) return;
        ensureRuntimeOwnership();
        frameRequested = true;
        try {
            host.requestFrame(this);
        } catch (RuntimeException error) {
            frameRequested = false;
            throw error;
        } catch (Error error) {
            frameRequested = false;
            throw error;
        }
    }

    private void cancelEmptyHostRequest() {
        if (!frameRequested) return;
        try {
            host.cancelFrame(this);
            frameRequested = false;
        } catch (RuntimeException error) {
            // Keep ownership until the possibly still-armed callback arrives as a no-op.
            throw error;
        } catch (Error error) {
            throw error;
        }
    }

    private void ensureRuntimeOwnership() {
        if (runtimeResourceSlot == NO_SLOT) {
            runtimeResourceSlot = runtime.registerOwnedResource(this);
        }
    }

    private void releaseRuntimeOwnershipIfIdle() {
        if (runtimeResourceSlot == NO_SLOT
                || frameRequested
                || insideFrame
                || pendingHead != NO_SLOT
                || runningHead != NO_SLOT) {
            return;
        }
        int slot = runtimeResourceSlot;
        runtimeResourceSlot = NO_SLOT;
        runtime.unregisterOwnedResource(this, slot);
    }

    private int allocateSlot(FrameRequestCallback callback, long frameSequence) {
        final int slot;
        if (freeSlotHead != NO_SLOT) {
            slot = freeSlotHead;
            freeSlotHead = nextFreeSlot[slot];
            nextFreeSlot[slot] = NO_SLOT;
            if (generations[slot] >= MAX_HANDLE_GENERATION) {
                throw new IllegalStateException("Animation-frame handle generation exhausted");
            }
            generations[slot]++;
        } else {
            if (slotCount >= HANDLE_INDEX_MASK) {
                throw new IllegalStateException("Too many concurrent animation-frame callbacks");
            }
            ensureCapacity(slotCount + 1);
            slot = slotCount++;
            generations[slot] = 1L;
        }

        callbacks[slot] = callback;
        frameSequences[slot] = frameSequence;
        nextOrder[slot] = NO_SLOT;
        previousOrder[slot] = NO_SLOT;
        active[slot] = true;
        cancelledRunning[slot] = false;
        return slot;
    }

    private void releaseSlot(int slot) {
        callbacks[slot] = null;
        frameSequences[slot] = 0L;
        nextOrder[slot] = NO_SLOT;
        previousOrder[slot] = NO_SLOT;
        active[slot] = false;
        cancelledRunning[slot] = false;

        if (generations[slot] < MAX_HANDLE_GENERATION) {
            nextFreeSlot[slot] = freeSlotHead;
            freeSlotHead = slot;
        } else {
            nextFreeSlot[slot] = NO_SLOT;
        }
    }

    private void appendPending(int slot) {
        if (pendingTail == NO_SLOT) {
            pendingHead = slot;
        } else {
            nextOrder[pendingTail] = slot;
            previousOrder[slot] = pendingTail;
        }
        pendingTail = slot;
    }

    private void unlinkPending(int slot) {
        int previous = previousOrder[slot];
        int next = nextOrder[slot];
        if (previous == NO_SLOT) {
            pendingHead = next;
        } else {
            nextOrder[previous] = next;
        }
        if (next == NO_SLOT) {
            pendingTail = previous;
        } else {
            previousOrder[next] = previous;
        }
        previousOrder[slot] = NO_SLOT;
        nextOrder[slot] = NO_SLOT;
    }

    private void rollbackPendingSlot(int slot) {
        unlinkPending(slot);
        releaseSlot(slot);
        if (pendingHead == NO_SLOT) pendingFrameSequence = 0L;
        releaseRuntimeOwnershipIfIdle();
    }

    private void discardUnreachedRunningCallbacks() {
        while (runningHead != NO_SLOT) {
            int slot = runningHead;
            runningHead = nextOrder[slot];
            releaseSlot(slot);
        }
        runningTail = NO_SLOT;
    }

    private long encodeHandle(int slot) {
        return (generations[slot] << HANDLE_INDEX_BITS) | (slot + 1L);
    }

    private int lookupHandle(double handle) {
        if (!(handle > 0.0) || handle > MAX_SAFE_HANDLE) return NO_SLOT;
        long encoded = (long) handle;
        if ((double) encoded != handle) return NO_SLOT;
        int encodedIndex = (int) (encoded & HANDLE_INDEX_MASK);
        if (encodedIndex == 0) return NO_SLOT;
        int slot = encodedIndex - 1;
        long generation = encoded >>> HANDLE_INDEX_BITS;
        if (generation == 0L
                || slot >= slotCount
                || !active[slot]
                || generations[slot] != generation) {
            return NO_SLOT;
        }
        return slot;
    }

    private long allocateFrameSequence() {
        if (nextFrameSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Animation-frame sequence space exhausted");
        }
        return nextFrameSequence++;
    }

    private void ensureCapacity(int required) {
        if (required <= callbacks.length) return;
        int oldCapacity = callbacks.length;
        if (oldCapacity > Integer.MAX_VALUE / 2) {
            throw new IllegalStateException("Animation-frame callback capacity exhausted");
        }
        int newCapacity = oldCapacity * 2;
        while (newCapacity < required) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = required;
                break;
            }
            newCapacity *= 2;
        }

        FrameRequestCallback[] grownCallbacks = new FrameRequestCallback[newCapacity];
        long[] grownGenerations = new long[newCapacity];
        long[] grownFrameSequences = new long[newCapacity];
        int[] grownNextOrder = new int[newCapacity];
        int[] grownPreviousOrder = new int[newCapacity];
        int[] grownNextFreeSlot = new int[newCapacity];
        boolean[] grownActive = new boolean[newCapacity];
        boolean[] grownCancelledRunning = new boolean[newCapacity];
        System.arraycopy(callbacks, 0, grownCallbacks, 0, oldCapacity);
        System.arraycopy(generations, 0, grownGenerations, 0, oldCapacity);
        System.arraycopy(frameSequences, 0, grownFrameSequences, 0, oldCapacity);
        System.arraycopy(nextOrder, 0, grownNextOrder, 0, oldCapacity);
        System.arraycopy(previousOrder, 0, grownPreviousOrder, 0, oldCapacity);
        System.arraycopy(nextFreeSlot, 0, grownNextFreeSlot, 0, oldCapacity);
        System.arraycopy(active, 0, grownActive, 0, oldCapacity);
        System.arraycopy(cancelledRunning, 0, grownCancelledRunning, 0, oldCapacity);
        callbacks = grownCallbacks;
        generations = grownGenerations;
        frameSequences = grownFrameSequences;
        nextOrder = grownNextOrder;
        previousOrder = grownPreviousOrder;
        nextFreeSlot = grownNextFreeSlot;
        active = grownActive;
        cancelledRunning = grownCancelledRunning;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("AnimationFrameScheduler is closed");
        }
    }

    private static void rethrowIfFatal(Throwable error) {
        if (error instanceof ThreadDeath) throw (ThreadDeath) error;
        if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
        if (error instanceof LinkageError) throw (LinkageError) error;
    }

    private static void throwAsUnchecked(Throwable error) {
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        throw new IllegalStateException("Animation-frame cancellation failed", error);
    }
}
