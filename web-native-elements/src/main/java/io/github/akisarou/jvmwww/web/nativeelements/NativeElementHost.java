package io.github.akisarou.jvmwww.web.nativeelements;

/**
 * Renderer boundary used by owner-confined React Native element values.
 *
 * <p>Every method is synchronous and must be called only on the runtime owner. Element identities
 * are opaque, generation-safe values owned by the renderer. A measurement returning {@code true}
 * must call {@link NativeElementRectSink#setRect} exactly once before returning; returning
 * {@code false} must not write the sink.</p>
 */
public interface NativeElementHost {
    boolean isConnected(long elementIdentity);

    String getTagName(long elementIdentity);

    String getId(long elementIdentity);

    boolean measureBoundingClientRect(
            long elementIdentity,
            boolean includeTransform,
            NativeElementRectSink sink);
}
