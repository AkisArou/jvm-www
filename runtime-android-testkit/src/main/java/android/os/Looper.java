package android.os;

import java.util.ArrayList;
import java.util.List;

/** Minimal deterministic Looper model used only by runtime-android-testkit. */
public final class Looper {
    private static final ThreadLocal<Looper> CURRENT = new ThreadLocal<Looper>();

    private final Thread ownerThread = Thread.currentThread();
    private final List<Entry> queue = new ArrayList<Entry>();
    private long nextSequence;
    private boolean accepting = true;

    private Looper() {}

    public static void prepare() {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Only one Looper may be created per thread");
        }
        CURRENT.set(new Looper());
    }

    public static Looper myLooper() {
        return CURRENT.get();
    }

    public Thread getThread() {
        return ownerThread;
    }

    synchronized boolean enqueue(Runnable callback, long whenUptimeMillis) {
        if (!accepting) {
            return false;
        }
        queue.add(new Entry(callback, whenUptimeMillis, nextSequence++));
        return true;
    }

    synchronized void remove(Runnable callback) {
        for (int index = queue.size() - 1; index >= 0; index--) {
            if (queue.get(index).callback == callback) {
                queue.remove(index);
            }
        }
    }

    public boolean runOneDueForTest() {
        assertOwnerThread();
        Entry selected = null;
        int selectedIndex = -1;
        synchronized (this) {
            for (int index = 0; index < queue.size(); index++) {
                Entry candidate = queue.get(index);
                if (candidate.whenUptimeMillis > SystemClock.uptimeMillis()) {
                    continue;
                }
                if (selected == null
                        || candidate.whenUptimeMillis < selected.whenUptimeMillis
                        || (candidate.whenUptimeMillis == selected.whenUptimeMillis
                                && candidate.sequence < selected.sequence)) {
                    selected = candidate;
                    selectedIndex = index;
                }
            }
            if (selected != null) {
                queue.remove(selectedIndex);
            }
        }
        if (selected == null) {
            return false;
        }
        selected.callback.run();
        return true;
    }

    public synchronized int pendingCountForTest() {
        return queue.size();
    }

    public synchronized long earliestUptimeMillisForTest() {
        if (queue.isEmpty()) {
            throw new IllegalStateException("No callback is queued");
        }
        long earliest = Long.MAX_VALUE;
        for (Entry entry : queue) {
            if (entry.whenUptimeMillis < earliest) {
                earliest = entry.whenUptimeMillis;
            }
        }
        return earliest;
    }

    public synchronized void resetForTest() {
        assertOwnerThread();
        queue.clear();
        nextSequence = 0L;
        accepting = true;
    }

    public synchronized void setAcceptingForTest(boolean value) {
        assertOwnerThread();
        accepting = value;
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != ownerThread || myLooper() != this) {
            throw new IllegalStateException("Fake Looper driven outside its owner thread");
        }
    }

    private static final class Entry {
        final Runnable callback;
        final long whenUptimeMillis;
        final long sequence;

        Entry(Runnable callback, long whenUptimeMillis, long sequence) {
            this.callback = callback;
            this.whenUptimeMillis = whenUptimeMillis;
            this.sequence = sequence;
        }
    }
}
