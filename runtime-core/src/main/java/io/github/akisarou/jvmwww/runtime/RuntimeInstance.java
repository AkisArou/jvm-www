package io.github.akisarou.jvmwww.runtime;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-instance owner scheduler for generated TypeScript code.
 *
 * <p>The class deliberately separates host tasks from microtasks. Android callbacks, timer fires,
 * network completions, and WebSocket messages enter as host tasks. Promise reactions and
 * {@code queueMicrotask} callbacks enter the FIFO microtask queue. The queue is drained to
 * exhaustion only when the outermost host turn exits.</p>
 *
 * <p>Generated code should use {@link #enterHostTurn()} and {@link #leaveHostTurn()} in a
 * {@code try/finally} pair. This avoids allocating a lambda or {@link Runnable} for every Android
 * callback.</p>
 */
public final class RuntimeInstance implements AutoCloseable {
    public static final int DEFAULT_HOST_TASKS_PER_WAKE = 64;

    private static final int WAKE_IDLE = 0;
    private static final int WAKE_SCHEDULED_OR_RUNNING = 1;

    private final OwnerExecutor ownerExecutor;
    private final RuntimeErrorReporter errorReporter;
    private final PromiseRejectionTracker rejectionTracker;
    private final int hostTasksPerWake;
    private final TimerHost timerHost;

    /** Owner-thread-only queues. */
    private final ArrayDeque<RuntimeTask> microtasks = new ArrayDeque<RuntimeTask>();
    private final ArrayDeque<JsPromise> unhandledRejectionCandidates =
            new ArrayDeque<JsPromise>();

    /** Multi-producer, single-consumer ingress queue. */
    private final ConcurrentLinkedQueue<AdmittedTask> admittedHostTasks =
            new ConcurrentLinkedQueue<AdmittedTask>();

    /** Coalesces both a scheduled callback and a callback currently draining work. */
    private final AtomicInteger wakeState = new AtomicInteger(WAKE_IDLE);

    /**
     * Changes after work publication and before a producer observes {@link #wakeState}. The owner
     * samples it while releasing a wake so an admission that saw the old scheduled state cannot be
     * lost even if it races the final queue check.
     */
    private final AtomicLong publishedWorkVersion = new AtomicLong();
    private final AtomicBoolean acceptingHostTasks = new AtomicBoolean(true);

    /** One reusable callback per runtime instance, rather than one allocation per wake. */
    private final Runnable wakeCallback = new Runnable() {
        @Override
        public void run() {
            runScheduledWake();
        }
    };

    /** Owner-thread-only state. */
    private int hostTurnDepth;
    private boolean drainingMicrotasks;
    private RuntimeTimerQueue timerQueue;

    /**
     * Published for foreign-thread terminal cleanup. The registry itself owns synchronization and
     * is allocated only after the first long-lived resource is reached.
     */
    private volatile RuntimeResourceRegistry resourceRegistry;
    private volatile boolean closed;

    public RuntimeInstance(OwnerExecutor ownerExecutor, RuntimeErrorReporter errorReporter) {
        this(
                ownerExecutor,
                errorReporter,
                PromiseRejectionTracker.NONE,
                DEFAULT_HOST_TASKS_PER_WAKE,
                TimerHost.UNSUPPORTED);
    }

    public RuntimeInstance(
            OwnerExecutor ownerExecutor,
            RuntimeErrorReporter errorReporter,
            int hostTasksPerWake) {
        this(
                ownerExecutor,
                errorReporter,
                PromiseRejectionTracker.NONE,
                hostTasksPerWake,
                TimerHost.UNSUPPORTED);
    }

    public RuntimeInstance(
            OwnerExecutor ownerExecutor,
            RuntimeErrorReporter errorReporter,
            PromiseRejectionTracker rejectionTracker) {
        this(
                ownerExecutor,
                errorReporter,
                rejectionTracker,
                DEFAULT_HOST_TASKS_PER_WAKE,
                TimerHost.UNSUPPORTED);
    }

    public RuntimeInstance(
            OwnerExecutor ownerExecutor,
            RuntimeErrorReporter errorReporter,
            TimerHost timerHost) {
        this(
                ownerExecutor,
                errorReporter,
                PromiseRejectionTracker.NONE,
                DEFAULT_HOST_TASKS_PER_WAKE,
                timerHost);
    }

    public RuntimeInstance(
            OwnerExecutor ownerExecutor,
            RuntimeErrorReporter errorReporter,
            PromiseRejectionTracker rejectionTracker,
            int hostTasksPerWake) {
        this(
                ownerExecutor,
                errorReporter,
                rejectionTracker,
                hostTasksPerWake,
                TimerHost.UNSUPPORTED);
    }

    public RuntimeInstance(
            OwnerExecutor ownerExecutor,
            RuntimeErrorReporter errorReporter,
            PromiseRejectionTracker rejectionTracker,
            TimerHost timerHost) {
        this(
                ownerExecutor,
                errorReporter,
                rejectionTracker,
                DEFAULT_HOST_TASKS_PER_WAKE,
                timerHost);
    }

    public RuntimeInstance(
            OwnerExecutor ownerExecutor,
            RuntimeErrorReporter errorReporter,
            PromiseRejectionTracker rejectionTracker,
            int hostTasksPerWake,
            TimerHost timerHost) {
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        this.errorReporter = Objects.requireNonNull(errorReporter, "errorReporter");
        this.rejectionTracker = Objects.requireNonNull(rejectionTracker, "rejectionTracker");
        this.timerHost = Objects.requireNonNull(timerHost, "timerHost");
        if (hostTasksPerWake < 1) {
            throw new IllegalArgumentException("hostTasksPerWake must be at least 1");
        }
        this.hostTasksPerWake = hostTasksPerWake;
    }

    /** Allocates a pending Promise owned by this runtime. */
    public JsPromise newPromise() {
        assertLanguageExecution();
        return new JsPromise(this);
    }

    /**
     * Allocates a pending Promise that is also its foreign completion token and admitted host task.
     *
     * <p>Capability providers return the same object as a {@link JsPromise} and retain it in their
     * platform callback. Completion methods are thread-safe and always settle on this runtime's
     * owner through the foreign ingress queue.</p>
     */
    public PlatformPromise newPlatformPromise() {
        assertLanguageExecution();
        return new PlatformPromise(this);
    }

    /**
     * Registers a long-lived host resource and returns its reusable integer slot.
     *
     * <p>Registration is owner-confined, but {@link #unregisterOwnedResource} may be called by a
     * foreign terminal callback. The registry is one lazy per-runtime object with reusable array
     * slots; this method creates no per-registration node or boxed index.</p>
     */
    public int registerOwnedResource(RuntimeOwnedResource resource) {
        assertLanguageExecution();
        RuntimeOwnedResource checked = Objects.requireNonNull(resource, "resource");
        if (!acceptingHostTasks.get()) {
            throw new IllegalStateException("RuntimeInstance is closing");
        }
        RuntimeResourceRegistry registry = resourceRegistry;
        if (registry == null) {
            registry = new RuntimeResourceRegistry();
            resourceRegistry = registry;
        }
        return registry.register(checked);
    }

    /**
     * Releases one exact runtime-resource slot. This method is safe to call from any thread.
     *
     * <p>The identity check makes a stale slot harmless after reuse. A false result means the
     * resource was already released or the runtime detached the registry for shutdown.</p>
     */
    public boolean unregisterOwnedResource(RuntimeOwnedResource resource, int slot) {
        RuntimeOwnedResource checked = Objects.requireNonNull(resource, "resource");
        if (slot < 0) {
            return false;
        }
        RuntimeResourceRegistry registry = resourceRegistry;
        return registry != null && registry.unregister(checked, slot);
    }

    /** Registers a one-shot timer and returns an exactly representable number handle. */
    public double setTimeout(RuntimeTask callback, double delayMilliseconds) {
        assertLanguageExecution();
        return getOrCreateTimerQueue().setTimeout(callback, delayMilliseconds);
    }

    /** Registers a non-overlapping interval in the same logical timer queue. */
    public double setInterval(RuntimeTask callback, double delayMilliseconds) {
        assertLanguageExecution();
        return getOrCreateTimerQueue().setInterval(callback, delayMilliseconds);
    }

    /** Cancels either timer kind; invalid, stale, and already-fired handles are no-ops. */
    public void clearTimeout(double handle) {
        assertLanguageExecution();
        if (timerQueue != null) {
            timerQueue.clearTimer(handle);
        }
    }

    /** Cancels either timer kind; timeout and interval handles deliberately share one map. */
    public void clearInterval(double handle) {
        assertLanguageExecution();
        if (timerQueue != null) {
            timerQueue.clearTimer(handle);
        }
    }

    private RuntimeTimerQueue getOrCreateTimerQueue() {
        if (timerQueue == null) {
            timerQueue = new RuntimeTimerQueue(this, timerHost, hostTasksPerWake);
        }
        return timerQueue;
    }

    /** Begins a host entry turn on the owner thread. Calls may nest. */
    public void enterHostTurn() {
        assertOwnerThread();
        ensureOpen();
        hostTurnDepth++;
    }

    /**
     * Ends a host entry turn and performs a microtask checkpoint after the outermost turn.
     *
     * <p>This method must be called from {@code finally} for every successful call to
     * {@link #enterHostTurn()}.</p>
     */
    public void leaveHostTurn() {
        assertOwnerThread();
        if (hostTurnDepth == 0) {
            throw new IllegalStateException("leaveHostTurn called without a matching enterHostTurn");
        }

        hostTurnDepth--;
        if (hostTurnDepth == 0 && !drainingMicrotasks && !closed) {
            drainMicrotasksToExhaustion();
            reportUnhandledRejections();
        }
    }

    /**
     * Appends a microtask on the owner thread.
     *
     * <p>Enqueuing during an active turn or checkpoint performs no owner post. Enqueuing while the
     * runtime is idle requests one coalesced wake so the checkpoint can run.</p>
     */
    public void queueMicrotask(RuntimeTask task) {
        assertOwnerThread();
        ensureOpen();
        microtasks.addLast(Objects.requireNonNull(task, "task"));

        if (hostTurnDepth == 0 && !drainingMicrotasks) {
            publishWakeWork();
            requestOwnerWake();
        }
    }

    /**
     * Admits a copied or retained host event from any thread.
     *
     * <p>The task is never executed by the calling foreign thread. A burst admitted while idle
     * produces one coalesced owner post. The return value is false when the runtime no longer
     * accepts work; in that case the task has been discarded.</p>
     */
    public boolean admitHostTask(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        if (!acceptingHostTasks.get()) {
            discardTask(task);
            return false;
        }

        AdmittedTask admittedTask = new AdmittedTask(task);
        admittedHostTasks.add(admittedTask);

        // Close can race between the first check and publication. Remove our exact queue node when
        // possible; otherwise close has already taken responsibility for discarding it.
        if (!acceptingHostTasks.get()) {
            if (admittedHostTasks.remove(admittedTask)) {
                discardTask(task);
            }
            return false;
        }

        publishWakeWork();
        try {
            requestOwnerWake();
        } catch (RuntimeException error) {
            discardFailedAdmission(admittedTask);
            throw error;
        } catch (Error error) {
            discardFailedAdmission(admittedTask);
            throw error;
        }
        return true;
    }

    private void discardFailedAdmission(AdmittedTask admittedTask) {
        // A failed OwnerExecutor post guarantees that this wake was not queued. Remove the exact
        // admission while it is still owner-inaccessible. If removal loses, close already polled
        // the node and owns its discard.
        if (admittedHostTasks.remove(admittedTask)) {
            discardTask(admittedTask.task);
        }
    }

    public boolean isOwnerThread() {
        return ownerExecutor.isOwnerThread();
    }

    public boolean isInsideHostTurn() {
        assertOwnerThread();
        return hostTurnDepth != 0;
    }

    public boolean isDrainingMicrotasks() {
        assertOwnerThread();
        return drainingMicrotasks;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Stops admission, cancels every still-owned host resource, and discards queued work. A callback
     * already posted to the owner may still arrive, but it becomes a no-op.
     */
    @Override
    public void close() {
        assertOwnerThread();
        if (closed) {
            return;
        }
        if (hostTurnDepth != 0 || drainingMicrotasks) {
            throw new IllegalStateException("RuntimeInstance cannot close during an active turn");
        }

        acceptingHostTasks.set(false);

        RuntimeResourceRegistry registry = resourceRegistry;
        RuntimeOwnedResource[] ownedResources = registry == null ? null : registry.detach();
        resourceRegistry = null;

        // Owner-confined resources may need to detach AbortSignal algorithms. Give shutdown a
        // cleanup turn without running a checkpoint; any accidentally queued microtask is discarded
        // below rather than executed after lifecycle teardown has begun.
        Throwable fatalResourceFailure;
        hostTurnDepth = 1;
        try {
            fatalResourceFailure = closeOwnedResources(ownedResources);
        } finally {
            hostTurnDepth = 0;
            closed = true;
        }

        if (timerQueue != null) {
            timerQueue.close();
        }

        AdmittedTask admittedTask;
        while ((admittedTask = admittedHostTasks.poll()) != null) {
            discardTask(admittedTask.task);
        }
        RuntimeTask task;
        while ((task = microtasks.pollFirst()) != null) {
            discardTask(task);
        }
        JsPromise promise;
        while ((promise = unhandledRejectionCandidates.pollFirst()) != null) {
            promise.setRejectionQueued(false);
        }

        // A previously posted callback cannot necessarily be removed from the host executor. It
        // will observe closed and return. Resetting allows no future admission because accepting is
        // already false.
        wakeState.set(WAKE_IDLE);

        if (fatalResourceFailure != null) {
            rethrowIfFatal(fatalResourceFailure);
        }
    }

    private Throwable closeOwnedResources(RuntimeOwnedResource[] resources) {
        if (resources == null) {
            return null;
        }
        Throwable firstFatal = null;
        for (int index = resources.length - 1; index >= 0; index--) {
            RuntimeOwnedResource resource = resources[index];
            resources[index] = null;
            if (resource == null) {
                continue;
            }
            try {
                resource.closeForRuntime();
            } catch (Throwable error) {
                if (isFatal(error)) {
                    firstFatal = rememberFailure(firstFatal, error);
                } else {
                    try {
                        report(RuntimeErrorPhase.DISCARD, error);
                    } catch (Throwable reporterFatal) {
                        firstFatal = rememberFailure(firstFatal, reporterFatal);
                    }
                }
            }
        }
        return firstFatal;
    }

    private static Throwable rememberFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private void runScheduledWake() {
        assertOwnerThread();

        try {
            if (closed) {
                return;
            }

            int processed = 0;
            AdmittedTask admittedTask;
            while (processed < hostTasksPerWake
                    && (admittedTask = admittedHostTasks.poll()) != null) {
                executeHostTask(admittedTask.task);
                processed++;
                if (closed) {
                    return;
                }
            }

            // queueMicrotask may have been called while the owner was idle. Entering and leaving an
            // empty host turn establishes the required checkpoint without representing a microtask
            // as a host task.
            if (processed == 0 && !microtasks.isEmpty()) {
                enterHostTurn();
                leaveHostTurn();
            }
        } finally {
            // Lost-wake avoidance has three producer cases:
            // 1. publication before this sample is visible through the version read;
            // 2. publication before IDLE but after this sample changes the second version read;
            // 3. publication after IDLE posts the wake itself.
            long versionBeforeIdle = publishedWorkVersion.get();
            wakeState.set(WAKE_IDLE);
            boolean pending = hasPendingWakeWork();
            long versionAfterCheck = publishedWorkVersion.get();
            if (!closed && (pending || versionAfterCheck != versionBeforeIdle)) {
                requestOwnerWake();
            }
        }
    }

    void executeHostTask(RuntimeTask task) {
        enterHostTurn();
        try {
            task.execute(this);
        } catch (Throwable error) {
            rethrowIfFatal(error);
            report(RuntimeErrorPhase.HOST_TASK, error);
        } finally {
            leaveHostTurn();
        }
    }

    private void drainMicrotasksToExhaustion() {
        drainingMicrotasks = true;
        try {
            RuntimeTask task;
            while ((task = microtasks.pollFirst()) != null) {
                try {
                    task.execute(this);
                } catch (Throwable error) {
                    rethrowIfFatal(error);
                    report(RuntimeErrorPhase.MICROTASK, error);
                }
            }
        } finally {
            drainingMicrotasks = false;
        }
    }

    void notePromiseRejected(JsPromise promise) {
        assertLanguageExecution();
        if (!promise.isRejectionQueued()) {
            promise.setRejectionQueued(true);
            unhandledRejectionCandidates.addLast(promise);
        }
    }

    void notifyPromiseHandled(JsPromise promise) {
        assertLanguageExecution();
        notifyRejectionTracker(false, promise);
    }

    private void reportUnhandledRejections() {
        JsPromise promise;
        while ((promise = unhandledRejectionCandidates.pollFirst()) != null) {
            promise.setRejectionQueued(false);
            if (promise.shouldReportUnhandled()) {
                promise.markReportedUnhandled();
                notifyRejectionTracker(true, promise);
            }
        }
    }

    private void notifyRejectionTracker(boolean unhandled, JsPromise promise) {
        try {
            if (unhandled) {
                rejectionTracker.onUnhandled(this, promise);
            } else {
                rejectionTracker.onHandled(this, promise);
            }
        } catch (Throwable error) {
            rethrowIfFatal(error);
            report(RuntimeErrorPhase.REJECTION_TRACKER, error);
        }
    }

    private void publishWakeWork() {
        publishedWorkVersion.incrementAndGet();
    }

    private void requestOwnerWake() {
        if (closed) {
            return;
        }
        if (wakeState.compareAndSet(WAKE_IDLE, WAKE_SCHEDULED_OR_RUNNING)) {
            try {
                ownerExecutor.post(wakeCallback);
            } catch (RuntimeException error) {
                wakeState.set(WAKE_IDLE);
                throw error;
            } catch (Error error) {
                wakeState.set(WAKE_IDLE);
                throw error;
            }
        }
    }

    private boolean hasPendingWakeWork() {
        // microtasks is owner-confined and this method is only called by the owner callback.
        return !admittedHostTasks.isEmpty() || !microtasks.isEmpty();
    }

    void discardTask(RuntimeTask task) {
        try {
            task.discard();
        } catch (Throwable error) {
            rethrowIfFatal(error);
            report(RuntimeErrorPhase.DISCARD, error);
        }
    }

    private void report(RuntimeErrorPhase phase, Throwable error) {
        try {
            errorReporter.report(this, phase, error);
        } catch (Throwable reporterFailure) {
            rethrowIfFatal(reporterFailure);
            // A broken reporting hook must not strand the scheduler in a half-drained state. Keep
            // the original failure available to diagnostics without making the hook recursive.
            if (reporterFailure != error) {
                error.addSuppressed(reporterFailure);
            }
            Thread current = Thread.currentThread();
            Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
            if (handler != null) {
                try {
                    handler.uncaughtException(current, error);
                } catch (Throwable handlerFailure) {
                    rethrowIfFatal(handlerFailure);
                    // The scheduler must remain structurally valid even when both reporting hooks
                    // are broken. There is no safe third callback to invoke here.
                }
            }
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

    private static boolean isFatal(Throwable error) {
        return error instanceof ThreadDeath
                || error instanceof VirtualMachineError
                || error instanceof LinkageError;
    }

    void assertOwnerAccess() {
        assertOwnerThread();
        ensureOpen();
    }

    void assertLanguageExecution() {
        assertOwnerAccess();
        if (hostTurnDepth == 0 && !drainingMicrotasks) {
            throw new IllegalStateException(
                    "Language runtime operation requires an active host turn or microtask");
        }
    }

    private void assertOwnerThread() {
        if (!ownerExecutor.isOwnerThread()) {
            throw new IllegalStateException("RuntimeInstance accessed outside its owner thread");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RuntimeInstance is closed");
        }
    }

    private static final class AdmittedTask {
        final RuntimeTask task;

        AdmittedTask(RuntimeTask task) {
            this.task = task;
        }
    }
}
