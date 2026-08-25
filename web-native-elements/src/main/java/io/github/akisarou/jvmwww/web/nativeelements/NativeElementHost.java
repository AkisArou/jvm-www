package io.github.akisarou.jvmwww.web.nativeelements;

/**
 * Renderer boundary used by owner-confined React Native element values.
 *
 * <p>Every method is synchronous and must be called only on the runtime owner. Element identities
 * are opaque, generation-safe values owned by the renderer. A rectangle measurement returning
 * {@code true} must call {@link NativeElementRectSink#setRect} exactly once before returning;
 * returning {@code false} must not write the sink. Scalar client and scroll metrics return positive
 * zero when the element or committed metric snapshot is unavailable.</p>
 */
public interface NativeElementHost {
    boolean isConnected(long elementIdentity);

    String getTagName(long elementIdentity);

    String getId(long elementIdentity);

    boolean measureBoundingClientRect(
            long elementIdentity,
            boolean includeTransform,
            NativeElementRectSink sink);

    double getClientWidth(long elementIdentity);

    double getClientHeight(long elementIdentity);

    double getClientTop(long elementIdentity);

    double getClientLeft(long elementIdentity);

    double getScrollLeft(long elementIdentity);

    double getScrollTop(long elementIdentity);

    double getScrollWidth(long elementIdentity);

    double getScrollHeight(long elementIdentity);
}
