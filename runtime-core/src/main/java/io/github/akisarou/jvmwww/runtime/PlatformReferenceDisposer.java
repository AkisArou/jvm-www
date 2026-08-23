package io.github.akisarou.jvmwww.runtime;

/**
 * Releases a copied or retained platform reference that will not be delivered to TypeScript.
 *
 * <p>The callback may run on the platform producer thread, the runtime owner, or the thread that
 * closes the runtime. It must be thread-safe, must not execute generated TypeScript, and should
 * not throw.</p>
 */
@FunctionalInterface
public interface PlatformReferenceDisposer {
    void dispose(Object value);
}
