package android.os;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic MessageQueue idle-handler model used only by the Android timing testkit. */
public final class MessageQueue {
    public interface IdleHandler {
        boolean queueIdle();
    }

    private final Looper looper;
    private final List<IdleHandler> idleHandlers = new ArrayList<IdleHandler>();
    private IdleHandler lastAddedHandler;
    private RuntimeException nextAddFailure;
    private RuntimeException nextRemoveFailure;
    private int addCount;
    private int removeCount;

    MessageQueue(Looper looper) {
        this.looper = looper;
    }

    public synchronized void addIdleHandler(IdleHandler handler) {
        IdleHandler checked = Objects.requireNonNull(handler, "handler");
        if (nextAddFailure != null) {
            RuntimeException failure = nextAddFailure;
            nextAddFailure = null;
            throw failure;
        }
        idleHandlers.add(checked);
        lastAddedHandler = checked;
        addCount++;
    }

    public synchronized void removeIdleHandler(IdleHandler handler) {
        IdleHandler checked = Objects.requireNonNull(handler, "handler");
        if (nextRemoveFailure != null) {
            RuntimeException failure = nextRemoveFailure;
            nextRemoveFailure = null;
            throw failure;
        }
        if (removeIdentity(checked)) {
            removeCount++;
        }
    }

    public boolean fireOneIdleForTest() {
        assertOwnerThread();
        final IdleHandler handler;
        synchronized (this) {
            if (idleHandlers.isEmpty()) {
                throw new AssertionError("No MessageQueue idle handler is registered");
            }
            handler = idleHandlers.get(0);
        }
        boolean keep = handler.queueIdle();
        if (!keep) {
            synchronized (this) {
                removeIdentity(handler);
            }
        }
        return keep;
    }

    public boolean invokeLastAddedForTest() {
        assertOwnerThread();
        final IdleHandler handler;
        synchronized (this) {
            handler = lastAddedHandler;
        }
        if (handler == null) {
            throw new AssertionError("No MessageQueue idle handler has been added");
        }
        return handler.queueIdle();
    }

    public synchronized void failNextAddForTest(RuntimeException failure) {
        assertOwnerThread();
        nextAddFailure = Objects.requireNonNull(failure, "failure");
    }

    public synchronized void failNextRemoveForTest(RuntimeException failure) {
        assertOwnerThread();
        nextRemoveFailure = Objects.requireNonNull(failure, "failure");
    }

    public synchronized int getIdleHandlerCountForTest() {
        assertOwnerThread();
        return idleHandlers.size();
    }

    public synchronized IdleHandler getFirstIdleHandlerForTest() {
        assertOwnerThread();
        return idleHandlers.isEmpty() ? null : idleHandlers.get(0);
    }

    public synchronized int getAddCountForTest() {
        assertOwnerThread();
        return addCount;
    }

    public synchronized int getRemoveCountForTest() {
        assertOwnerThread();
        return removeCount;
    }

    public synchronized void resetForTest() {
        assertOwnerThread();
        idleHandlers.clear();
        lastAddedHandler = null;
        nextAddFailure = null;
        nextRemoveFailure = null;
        addCount = 0;
        removeCount = 0;
    }

    private boolean removeIdentity(IdleHandler handler) {
        for (int index = 0; index < idleHandlers.size(); index++) {
            if (idleHandlers.get(index) == handler) {
                idleHandlers.remove(index);
                return true;
            }
        }
        return false;
    }

    private void assertOwnerThread() {
        if (Looper.myLooper() != looper) {
            throw new IllegalStateException("Fake MessageQueue driven outside its Looper");
        }
    }
}
