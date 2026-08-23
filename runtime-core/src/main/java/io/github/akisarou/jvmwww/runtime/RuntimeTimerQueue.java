package io.github.akisarou.jvmwww.runtime;

import java.util.Objects;

/** Owner-confined logical one-shot timer heap. */
final class RuntimeTimerQueue {
    static final long TIMEOUT_MAX_MILLIS = 2_147_483_647L;

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int INITIAL_CAPACITY = 16;

    // Handles remain exactly representable as a JavaScript number. Twenty low bits identify one
    // concurrent slot and the remaining 33 bits are its generation.
    private static final int HANDLE_INDEX_BITS = 20;
    private static final long HANDLE_INDEX_MASK = (1L << HANDLE_INDEX_BITS) - 1L;
    private static final long MAX_HANDLE_GENERATION = (1L << (53 - HANDLE_INDEX_BITS)) - 1L;
    private static final long MAX_SAFE_HANDLE = (1L << 53) - 1L;

    private final RuntimeInstance runtime;
    private final TimerHost host;
    private final int callbacksPerWake;

    private TimerEntry[] heap = new TimerEntry[INITIAL_CAPACITY];
    private int heapSize;

    private TimerEntry[] slots = new TimerEntry[INITIAL_CAPACITY];
    private int slotCount;
    private int freeSlotHead = -1;

    private long nextSequence;
    private long lastNowNanos = -1L;
    private boolean alarmArmed;
    private long armedDeadlineNanos;
    private boolean drainingWake;
    private boolean closed;

    /** One reusable host callback per runtime, never one Runnable per logical timer. */
    private final Runnable wakeCallback = new Runnable() {
        @Override
        public void run() {
            runTimerWake();
        }
    };

    RuntimeTimerQueue(RuntimeInstance runtime, TimerHost host, int callbacksPerWake) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.host = Objects.requireNonNull(host, "host");
        if (host == TimerHost.UNSUPPORTED) {
            throw new UnsupportedOperationException("RuntimeInstance has no timer host");
        }
        if (callbacksPerWake < 1) {
            throw new IllegalArgumentException("callbacksPerWake must be at least 1");
        }
        this.callbacksPerWake = callbacksPerWake;
    }

    double setTimeout(RuntimeTask callback, double delayMilliseconds) {
        runtime.assertLanguageExecution();
        ensureOpen();
        Objects.requireNonNull(callback, "callback");

        long nowNanos = readNowNanos();
        long delayMillis = coerceDelayMillis(delayMilliseconds);
        TimerEntry entry = allocateSlot();
        entry.deadlineNanos = saturatingAdd(nowNanos, delayMillis * NANOS_PER_MILLI);
        entry.sequence = allocateSequence();
        entry.callback = callback;
        push(entry);

        if (!drainingWake) {
            synchronizeAlarm();
        }
        return (double) encodeHandle(entry);
    }

    void clearTimeout(double handle) {
        runtime.assertLanguageExecution();
        if (closed) {
            return;
        }

        TimerEntry entry = lookupHandle(handle);
        if (entry == null) {
            return;
        }

        removeAt(entry.heapIndex);
        RuntimeTask callback = entry.callback;
        releaseSlot(entry);
        runtime.discardTask(callback);

        if (!drainingWake) {
            synchronizeAlarm();
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (alarmArmed) {
            host.disarm();
            alarmArmed = false;
        }

        while (heapSize != 0) {
            TimerEntry entry = removeAt(0);
            RuntimeTask callback = entry.callback;
            releaseSlot(entry);
            runtime.discardTask(callback);
        }
    }

    static long coerceDelayMillis(double delayMilliseconds) {
        // ScriptC's current static timer surface follows Node's clamp-and-truncate rule: NaN,
        // negative, sub-millisecond, Infinity, and values beyond TIMEOUT_MAX become 1 ms.
        if (!(delayMilliseconds >= 1.0) || delayMilliseconds > TIMEOUT_MAX_MILLIS) {
            return 1L;
        }
        return (long) delayMilliseconds;
    }

    private void runTimerWake() {
        if (closed || runtime.isClosed()) {
            return;
        }
        runtime.assertOwnerAccess();
        alarmArmed = false;
        drainingWake = true;
        try {
            int processed = 0;
            while (processed < callbacksPerWake && heapSize != 0) {
                long nowNanos = readNowNanos();
                TimerEntry first = heap[0];
                if (first.deadlineNanos > nowNanos) {
                    break;
                }

                TimerEntry due = removeAt(0);
                RuntimeTask callback = due.callback;
                releaseSlot(due);
                runtime.executeHostTask(callback);
                processed++;

                if (closed || runtime.isClosed()) {
                    return;
                }
            }
        } finally {
            drainingWake = false;
            if (!closed && !runtime.isClosed()) {
                synchronizeAlarm();
            }
        }
    }

    private void synchronizeAlarm() {
        if (closed) {
            return;
        }
        if (heapSize == 0) {
            if (alarmArmed) {
                host.disarm();
                alarmArmed = false;
            }
            return;
        }

        long nextDeadline = heap[0].deadlineNanos;
        if (alarmArmed && armedDeadlineNanos == nextDeadline) {
            return;
        }
        host.arm(nextDeadline, wakeCallback);
        alarmArmed = true;
        armedDeadlineNanos = nextDeadline;
    }

    private long readNowNanos() {
        long nowNanos = host.nowNanos();
        if (nowNanos < 0L) {
            throw new IllegalStateException("TimerHost returned a negative monotonic timestamp");
        }
        if (lastNowNanos != -1L && nowNanos < lastNowNanos) {
            throw new IllegalStateException("TimerHost monotonic timestamp moved backwards");
        }
        lastNowNanos = nowNanos;
        return nowNanos;
    }

    private TimerEntry allocateSlot() {
        TimerEntry entry;
        if (freeSlotHead != -1) {
            int index = freeSlotHead;
            entry = slots[index];
            freeSlotHead = entry.nextFreeSlot;
            entry.nextFreeSlot = -1;
            if (entry.generation >= MAX_HANDLE_GENERATION) {
                throw new IllegalStateException("Timer handle generation space exhausted");
            }
            entry.generation++;
        } else {
            if (slotCount >= HANDLE_INDEX_MASK) {
                throw new IllegalStateException("Too many concurrent timers");
            }
            ensureSlotCapacity(slotCount + 1);
            entry = new TimerEntry(slotCount);
            entry.generation = 1L;
            slots[slotCount] = entry;
            slotCount++;
        }

        entry.active = true;
        entry.heapIndex = -1;
        return entry;
    }

    private void releaseSlot(TimerEntry entry) {
        entry.active = false;
        entry.deadlineNanos = 0L;
        entry.sequence = 0L;
        entry.callback = null;
        entry.heapIndex = -1;

        // A slot at its maximum generation is retired rather than making its final stale handle
        // valid again. Exhausting this space would require billions of reuses of one exact slot.
        if (entry.generation < MAX_HANDLE_GENERATION) {
            entry.nextFreeSlot = freeSlotHead;
            freeSlotHead = entry.slotIndex;
        } else {
            entry.nextFreeSlot = -1;
        }
    }

    private long encodeHandle(TimerEntry entry) {
        return (entry.generation << HANDLE_INDEX_BITS) | (entry.slotIndex + 1L);
    }

    private TimerEntry lookupHandle(double handle) {
        if (!(handle > 0.0) || handle > MAX_SAFE_HANDLE) {
            return null;
        }
        long encoded = (long) handle;
        if ((double) encoded != handle) {
            return null;
        }

        int encodedIndex = (int) (encoded & HANDLE_INDEX_MASK);
        if (encodedIndex == 0) {
            return null;
        }
        int slotIndex = encodedIndex - 1;
        long generation = encoded >>> HANDLE_INDEX_BITS;
        if (generation == 0L || slotIndex >= slotCount) {
            return null;
        }

        TimerEntry entry = slots[slotIndex];
        if (entry == null || !entry.active || entry.generation != generation) {
            return null;
        }
        return entry;
    }

    private long allocateSequence() {
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Timer registration sequence exhausted");
        }
        return nextSequence++;
    }

    private void push(TimerEntry entry) {
        ensureHeapCapacity(heapSize + 1);
        int index = heapSize++;
        heap[index] = entry;
        entry.heapIndex = index;
        siftUp(index);
    }

    private TimerEntry removeAt(int index) {
        TimerEntry removed = heap[index];
        int lastIndex = --heapSize;
        TimerEntry moved = heap[lastIndex];
        heap[lastIndex] = null;

        if (index != lastIndex) {
            heap[index] = moved;
            moved.heapIndex = index;
            if (index > 0 && before(moved, heap[(index - 1) >>> 1])) {
                siftUp(index);
            } else {
                siftDown(index);
            }
        }
        removed.heapIndex = -1;
        return removed;
    }

    private void siftUp(int index) {
        TimerEntry entry = heap[index];
        while (index > 0) {
            int parentIndex = (index - 1) >>> 1;
            TimerEntry parent = heap[parentIndex];
            if (!before(entry, parent)) {
                break;
            }
            heap[index] = parent;
            parent.heapIndex = index;
            index = parentIndex;
        }
        heap[index] = entry;
        entry.heapIndex = index;
    }

    private void siftDown(int index) {
        TimerEntry entry = heap[index];
        int half = heapSize >>> 1;
        while (index < half) {
            int leftIndex = (index << 1) + 1;
            int rightIndex = leftIndex + 1;
            int smallestIndex = leftIndex;
            TimerEntry smallest = heap[leftIndex];
            if (rightIndex < heapSize && before(heap[rightIndex], smallest)) {
                smallestIndex = rightIndex;
                smallest = heap[rightIndex];
            }
            if (!before(smallest, entry)) {
                break;
            }
            heap[index] = smallest;
            smallest.heapIndex = index;
            index = smallestIndex;
        }
        heap[index] = entry;
        entry.heapIndex = index;
    }

    private static boolean before(TimerEntry left, TimerEntry right) {
        if (left.deadlineNanos != right.deadlineNanos) {
            return left.deadlineNanos < right.deadlineNanos;
        }
        return left.sequence < right.sequence;
    }

    private void ensureHeapCapacity(int required) {
        if (required <= heap.length) {
            return;
        }
        int capacity = heap.length << 1;
        while (capacity < required) {
            capacity <<= 1;
        }
        TimerEntry[] replacement = new TimerEntry[capacity];
        System.arraycopy(heap, 0, replacement, 0, heapSize);
        heap = replacement;
    }

    private void ensureSlotCapacity(int required) {
        if (required <= slots.length) {
            return;
        }
        int capacity = slots.length << 1;
        while (capacity < required && capacity < HANDLE_INDEX_MASK) {
            capacity <<= 1;
        }
        if (capacity > HANDLE_INDEX_MASK) {
            capacity = (int) HANDLE_INDEX_MASK;
        }
        TimerEntry[] replacement = new TimerEntry[capacity];
        System.arraycopy(slots, 0, replacement, 0, slotCount);
        slots = replacement;
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Runtime timer queue is closed");
        }
    }

    private static final class TimerEntry {
        final int slotIndex;
        long generation;
        long deadlineNanos;
        long sequence;
        RuntimeTask callback;
        int heapIndex = -1;
        int nextFreeSlot = -1;
        boolean active;

        TimerEntry(int slotIndex) {
            this.slotIndex = slotIndex;
        }
    }
}
