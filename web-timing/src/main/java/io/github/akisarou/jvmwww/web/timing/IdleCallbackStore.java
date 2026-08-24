package io.github.akisarou.jvmwww.web.timing;

/** Reusable parallel-array storage for one idle scheduler; no object is allocated per request. */
final class IdleCallbackStore {
    static final int NO_SLOT = -1;
    static final long NO_TIMEOUT = Long.MAX_VALUE;

    private static final int INITIAL_CAPACITY = 8;
    private static final byte LIST_NONE = 0;
    private static final byte LIST_PENDING = 1;
    private static final byte LIST_RUNNABLE = 2;
    private static final int HANDLE_INDEX_BITS = 20;
    private static final long HANDLE_INDEX_MASK = (1L << HANDLE_INDEX_BITS) - 1L;
    private static final long MAX_HANDLE_GENERATION =
            (1L << (53 - HANDLE_INDEX_BITS)) - 1L;
    private static final long MAX_SAFE_HANDLE = (1L << 53) - 1L;

    private IdleRequestCallback[] callbacks = new IdleRequestCallback[INITIAL_CAPACITY];
    private long[] generations = new long[INITIAL_CAPACITY];
    private long[] timeoutDeadlineNanos = new long[INITIAL_CAPACITY];
    private long[] timeoutSequences = new long[INITIAL_CAPACITY];
    private int[] nextOrder = new int[INITIAL_CAPACITY];
    private int[] previousOrder = new int[INITIAL_CAPACITY];
    private int[] nextFreeSlot = new int[INITIAL_CAPACITY];
    private int[] timeoutHeap = new int[INITIAL_CAPACITY];
    private int[] timeoutHeapPositions = new int[INITIAL_CAPACITY];
    private byte[] listKinds = new byte[INITIAL_CAPACITY];
    private boolean[] active = new boolean[INITIAL_CAPACITY];

    private int slotCount;
    private int activeCount;
    private int freeSlotHead = NO_SLOT;
    private int pendingHead = NO_SLOT;
    private int pendingTail = NO_SLOT;
    private int runnableHead = NO_SLOT;
    private int runnableTail = NO_SLOT;
    private int timeoutHeapSize;
    private long nextTimeoutSequence = 1L;

    IdleCallbackStore() {
        initializeSentinels(0, INITIAL_CAPACITY);
    }

    int allocate(IdleRequestCallback callback, long timeoutDeadline) {
        long timeoutSequence = 0L;
        if (timeoutDeadline != NO_TIMEOUT) {
            timeoutSequence = allocateTimeoutSequence();
        }

        final int slot;
        if (freeSlotHead != NO_SLOT) {
            slot = freeSlotHead;
            freeSlotHead = nextFreeSlot[slot];
            nextFreeSlot[slot] = NO_SLOT;
            if (generations[slot] >= MAX_HANDLE_GENERATION) {
                throw new IllegalStateException("Idle-callback handle generation exhausted");
            }
            generations[slot]++;
        } else {
            if (slotCount >= HANDLE_INDEX_MASK) {
                throw new IllegalStateException("Too many concurrent idle callbacks");
            }
            ensureCapacity(slotCount + 1);
            slot = slotCount++;
            generations[slot] = 1L;
        }

        callbacks[slot] = callback;
        timeoutDeadlineNanos[slot] = timeoutDeadline;
        timeoutSequences[slot] = timeoutSequence;
        nextOrder[slot] = NO_SLOT;
        previousOrder[slot] = NO_SLOT;
        timeoutHeapPositions[slot] = NO_SLOT;
        listKinds[slot] = LIST_NONE;
        active[slot] = true;
        activeCount++;
        appendPending(slot);
        if (timeoutDeadline != NO_TIMEOUT) {
            insertTimeout(slot);
        }
        return slot;
    }

    int activeCount() {
        return activeCount;
    }

    int firstRunnable() {
        return runnableHead;
    }

    int firstTimeout() {
        return timeoutHeapSize == 0 ? NO_SLOT : timeoutHeap[0];
    }

    long earliestTimeoutDeadline() {
        int slot = firstTimeout();
        return slot == NO_SLOT ? NO_TIMEOUT : timeoutDeadlineNanos[slot];
    }

    long timeoutDeadline(int slot) {
        return timeoutDeadlineNanos[slot];
    }

    void movePendingToRunnable() {
        if (pendingHead == NO_SLOT) {
            return;
        }
        if (runnableTail == NO_SLOT) {
            runnableHead = pendingHead;
        } else {
            nextOrder[runnableTail] = pendingHead;
            previousOrder[pendingHead] = runnableTail;
        }
        runnableTail = pendingTail;

        int slot = pendingHead;
        while (slot != NO_SLOT) {
            listKinds[slot] = LIST_RUNNABLE;
            slot = nextOrder[slot];
        }
        pendingHead = NO_SLOT;
        pendingTail = NO_SLOT;
    }

    IdleRequestCallback detach(int slot) {
        IdleRequestCallback callback = callbacks[slot];
        remove(slot);
        return callback;
    }

    void remove(int slot) {
        unlinkOrder(slot);
        removeTimeout(slot);
        releaseSlot(slot);
    }

    boolean isActive(int slot) {
        return slot >= 0 && slot < slotCount && active[slot];
    }

    double handle(int slot) {
        return (double) ((generations[slot] << HANDLE_INDEX_BITS) | (slot + 1L));
    }

    int lookupHandle(double handle) {
        if (!(handle > 0.0) || handle > MAX_SAFE_HANDLE) {
            return NO_SLOT;
        }
        long encoded = (long) handle;
        if ((double) encoded != handle) {
            return NO_SLOT;
        }
        int encodedIndex = (int) (encoded & HANDLE_INDEX_MASK);
        if (encodedIndex == 0) {
            return NO_SLOT;
        }
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

    void clear() {
        for (int slot = 0; slot < slotCount; slot++) {
            callbacks[slot] = null;
            active[slot] = false;
            listKinds[slot] = LIST_NONE;
            timeoutDeadlineNanos[slot] = NO_TIMEOUT;
            timeoutSequences[slot] = 0L;
            nextOrder[slot] = NO_SLOT;
            previousOrder[slot] = NO_SLOT;
            nextFreeSlot[slot] = NO_SLOT;
            timeoutHeapPositions[slot] = NO_SLOT;
        }
        activeCount = 0;
        freeSlotHead = NO_SLOT;
        pendingHead = NO_SLOT;
        pendingTail = NO_SLOT;
        runnableHead = NO_SLOT;
        runnableTail = NO_SLOT;
        timeoutHeapSize = 0;
    }

    private void appendPending(int slot) {
        listKinds[slot] = LIST_PENDING;
        if (pendingTail == NO_SLOT) {
            pendingHead = slot;
        } else {
            nextOrder[pendingTail] = slot;
            previousOrder[slot] = pendingTail;
        }
        pendingTail = slot;
    }

    private void unlinkOrder(int slot) {
        byte kind = listKinds[slot];
        if (kind == LIST_NONE) {
            return;
        }
        int previous = previousOrder[slot];
        int next = nextOrder[slot];
        if (kind == LIST_PENDING) {
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
        } else if (kind == LIST_RUNNABLE) {
            if (previous == NO_SLOT) {
                runnableHead = next;
            } else {
                nextOrder[previous] = next;
            }
            if (next == NO_SLOT) {
                runnableTail = previous;
            } else {
                previousOrder[next] = previous;
            }
        } else {
            throw new AssertionError("Unknown idle callback list kind: " + kind);
        }
        previousOrder[slot] = NO_SLOT;
        nextOrder[slot] = NO_SLOT;
        listKinds[slot] = LIST_NONE;
    }

    private void releaseSlot(int slot) {
        callbacks[slot] = null;
        timeoutDeadlineNanos[slot] = NO_TIMEOUT;
        timeoutSequences[slot] = 0L;
        nextOrder[slot] = NO_SLOT;
        previousOrder[slot] = NO_SLOT;
        timeoutHeapPositions[slot] = NO_SLOT;
        listKinds[slot] = LIST_NONE;
        active[slot] = false;
        activeCount--;

        if (generations[slot] < MAX_HANDLE_GENERATION) {
            nextFreeSlot[slot] = freeSlotHead;
            freeSlotHead = slot;
        } else {
            nextFreeSlot[slot] = NO_SLOT;
        }
    }

    private void insertTimeout(int slot) {
        int position = timeoutHeapSize++;
        timeoutHeap[position] = slot;
        timeoutHeapPositions[slot] = position;
        siftTimeoutUp(position);
    }

    private void removeTimeout(int slot) {
        int position = timeoutHeapPositions[slot];
        if (position == NO_SLOT) {
            return;
        }
        int lastPosition = --timeoutHeapSize;
        int replacement = timeoutHeap[lastPosition];
        timeoutHeapPositions[slot] = NO_SLOT;
        if (position == lastPosition) {
            return;
        }
        timeoutHeap[position] = replacement;
        timeoutHeapPositions[replacement] = position;
        if (!siftTimeoutDown(position)) {
            siftTimeoutUp(position);
        }
    }

    private void siftTimeoutUp(int start) {
        int position = start;
        int slot = timeoutHeap[position];
        while (position > 0) {
            int parent = (position - 1) >>> 1;
            int parentSlot = timeoutHeap[parent];
            if (!timeoutBefore(slot, parentSlot)) {
                break;
            }
            timeoutHeap[position] = parentSlot;
            timeoutHeapPositions[parentSlot] = position;
            position = parent;
        }
        timeoutHeap[position] = slot;
        timeoutHeapPositions[slot] = position;
    }

    private boolean siftTimeoutDown(int start) {
        int position = start;
        int slot = timeoutHeap[position];
        int half = timeoutHeapSize >>> 1;
        boolean moved = false;
        while (position < half) {
            int left = (position << 1) + 1;
            int right = left + 1;
            int child = left;
            int childSlot = timeoutHeap[left];
            if (right < timeoutHeapSize && timeoutBefore(timeoutHeap[right], childSlot)) {
                child = right;
                childSlot = timeoutHeap[right];
            }
            if (!timeoutBefore(childSlot, slot)) {
                break;
            }
            timeoutHeap[position] = childSlot;
            timeoutHeapPositions[childSlot] = position;
            position = child;
            moved = true;
        }
        timeoutHeap[position] = slot;
        timeoutHeapPositions[slot] = position;
        return moved;
    }

    private boolean timeoutBefore(int first, int second) {
        long firstDeadline = timeoutDeadlineNanos[first];
        long secondDeadline = timeoutDeadlineNanos[second];
        return firstDeadline < secondDeadline
                || (firstDeadline == secondDeadline
                        && timeoutSequences[first] < timeoutSequences[second]);
    }

    private long allocateTimeoutSequence() {
        if (nextTimeoutSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Idle timeout sequence space exhausted");
        }
        return nextTimeoutSequence++;
    }

    private void ensureCapacity(int required) {
        if (required <= callbacks.length) {
            return;
        }
        int oldCapacity = callbacks.length;
        if (oldCapacity > Integer.MAX_VALUE / 2) {
            throw new IllegalStateException("Idle callback capacity exhausted");
        }
        int newCapacity = oldCapacity * 2;
        while (newCapacity < required) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = required;
                break;
            }
            newCapacity *= 2;
        }

        IdleRequestCallback[] grownCallbacks = new IdleRequestCallback[newCapacity];
        long[] grownGenerations = new long[newCapacity];
        long[] grownTimeoutDeadlines = new long[newCapacity];
        long[] grownTimeoutSequences = new long[newCapacity];
        int[] grownNextOrder = new int[newCapacity];
        int[] grownPreviousOrder = new int[newCapacity];
        int[] grownNextFreeSlot = new int[newCapacity];
        int[] grownTimeoutHeap = new int[newCapacity];
        int[] grownTimeoutPositions = new int[newCapacity];
        byte[] grownListKinds = new byte[newCapacity];
        boolean[] grownActive = new boolean[newCapacity];

        System.arraycopy(callbacks, 0, grownCallbacks, 0, oldCapacity);
        System.arraycopy(generations, 0, grownGenerations, 0, oldCapacity);
        System.arraycopy(timeoutDeadlineNanos, 0, grownTimeoutDeadlines, 0, oldCapacity);
        System.arraycopy(timeoutSequences, 0, grownTimeoutSequences, 0, oldCapacity);
        System.arraycopy(nextOrder, 0, grownNextOrder, 0, oldCapacity);
        System.arraycopy(previousOrder, 0, grownPreviousOrder, 0, oldCapacity);
        System.arraycopy(nextFreeSlot, 0, grownNextFreeSlot, 0, oldCapacity);
        System.arraycopy(timeoutHeap, 0, grownTimeoutHeap, 0, timeoutHeapSize);
        System.arraycopy(timeoutHeapPositions, 0, grownTimeoutPositions, 0, oldCapacity);
        System.arraycopy(listKinds, 0, grownListKinds, 0, oldCapacity);
        System.arraycopy(active, 0, grownActive, 0, oldCapacity);

        callbacks = grownCallbacks;
        generations = grownGenerations;
        timeoutDeadlineNanos = grownTimeoutDeadlines;
        timeoutSequences = grownTimeoutSequences;
        nextOrder = grownNextOrder;
        previousOrder = grownPreviousOrder;
        nextFreeSlot = grownNextFreeSlot;
        timeoutHeap = grownTimeoutHeap;
        timeoutHeapPositions = grownTimeoutPositions;
        listKinds = grownListKinds;
        active = grownActive;
        initializeSentinels(oldCapacity, newCapacity);
    }

    private void initializeSentinels(int from, int to) {
        for (int index = from; index < to; index++) {
            timeoutDeadlineNanos[index] = NO_TIMEOUT;
            nextOrder[index] = NO_SLOT;
            previousOrder[index] = NO_SLOT;
            nextFreeSlot[index] = NO_SLOT;
            timeoutHeapPositions[index] = NO_SLOT;
        }
    }
}
