package io.github.akisarou.jvmwww.runtime;

/**
 * Thread-safe reusable-slot storage for resources owned by one RuntimeInstance.
 *
 * <p>The registry is allocated lazily once per runtime that reaches a long-lived resource. A
 * registration stores only an integer slot in the resource; there is no registration node, map
 * entry, boxed index, or per-operation atomic object.</p>
 */
final class RuntimeResourceRegistry {
    private static final int NO_SLOT = -1;
    private static final int INITIAL_CAPACITY = 4;

    private RuntimeOwnedResource[] resources =
            new RuntimeOwnedResource[INITIAL_CAPACITY];
    private int[] nextFreeSlot = new int[INITIAL_CAPACITY];
    private int firstFreeSlot = NO_SLOT;
    private int nextUnusedSlot;
    private int activeCount;

    synchronized int register(RuntimeOwnedResource resource) {
        if (resources == null) {
            throw new IllegalStateException("Runtime resource registry is closed");
        }

        final int slot;
        if (firstFreeSlot != NO_SLOT) {
            slot = firstFreeSlot;
            firstFreeSlot = nextFreeSlot[slot];
        } else {
            if (nextUnusedSlot == resources.length) {
                grow();
            }
            slot = nextUnusedSlot++;
        }

        resources[slot] = resource;
        nextFreeSlot[slot] = NO_SLOT;
        activeCount++;
        return slot;
    }

    synchronized boolean unregister(RuntimeOwnedResource resource, int slot) {
        if (resources == null
                || slot < 0
                || slot >= nextUnusedSlot
                || resources[slot] != resource) {
            return false;
        }

        resources[slot] = null;
        nextFreeSlot[slot] = firstFreeSlot;
        firstFreeSlot = slot;
        activeCount--;
        return true;
    }

    /**
     * Detaches the backing array without copying it. Registrations cannot be added after this call.
     */
    synchronized RuntimeOwnedResource[] detach() {
        RuntimeOwnedResource[] detached = activeCount == 0 ? null : resources;
        resources = null;
        nextFreeSlot = null;
        firstFreeSlot = NO_SLOT;
        nextUnusedSlot = 0;
        activeCount = 0;
        return detached;
    }

    private void grow() {
        int oldCapacity = resources.length;
        if (oldCapacity > Integer.MAX_VALUE / 2) {
            throw new IllegalStateException("Runtime resource slot space exhausted");
        }
        int newCapacity = oldCapacity * 2;
        RuntimeOwnedResource[] grownResources = new RuntimeOwnedResource[newCapacity];
        int[] grownNextFreeSlot = new int[newCapacity];
        System.arraycopy(resources, 0, grownResources, 0, oldCapacity);
        System.arraycopy(nextFreeSlot, 0, grownNextFreeSlot, 0, oldCapacity);
        resources = grownResources;
        nextFreeSlot = grownNextFreeSlot;
    }
}
