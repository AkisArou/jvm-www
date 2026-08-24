package io.github.akisarou.jvmwww.runtime;

/**
 * A long-lived host resource whose lifetime is bounded by one {@link RuntimeInstance}.
 *
 * <p>Capability objects register themselves while executing on the runtime owner. The runtime calls
 * {@link #closeForRuntime()} on that same owner when it closes, even when the resource has not yet
 * published a host task. Implementations must cancel or release platform work without executing
 * generated TypeScript. The callback should not throw.</p>
 */
public interface RuntimeOwnedResource {
    /** Cancels or releases the resource during owner-thread runtime shutdown. */
    void closeForRuntime();
}
