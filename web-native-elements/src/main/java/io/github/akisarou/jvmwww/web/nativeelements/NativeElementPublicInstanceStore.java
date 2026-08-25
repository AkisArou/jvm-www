package io.github.akisarou.jvmwww.web.nativeelements;

/**
 * Renderer-owned public-instance table for opaque native element identities.
 *
 * <p>The store must return the stable public wrapper for one still-mounted exact identity. A
 * released identity must never resolve to a wrapper created for a reused renderer slot.</p>
 */
public interface NativeElementPublicInstanceStore {
    ReactNativeElement getPublicInstance(long elementIdentity);

    ReactNativeElement registerPublicInstance(
            long elementIdentity, ReactNativeElement publicInstance);
}
