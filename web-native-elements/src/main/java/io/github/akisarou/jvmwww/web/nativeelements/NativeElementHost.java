package io.github.akisarou.jvmwww.web.nativeelements;

/**
 * Renderer boundary used by owner-confined React Native element values.
 *
 * <p>Every method is synchronous and must be called only on the runtime owner. Element identities
 * are opaque, generation-safe values owned by the renderer. A rectangle, offset, or element-
 * relation read returning {@code true} must write its supplied sink exactly once before returning;
 * returning {@code false} must not write. Scalar client and scroll metrics return positive zero,
 * and child-element counts return zero, when the element or committed snapshot is unavailable.</p>
 */
public interface NativeElementHost extends NativeElementPublicInstanceStore {
    boolean isConnected(long elementIdentity);

    String getTagName(long elementIdentity);

    String getId(long elementIdentity);

    boolean measureBoundingClientRect(
            long elementIdentity,
            boolean includeTransform,
            NativeElementRectSink sink);

    boolean measureOffset(long elementIdentity, NativeElementOffsetSink sink);

    boolean readParentElement(long elementIdentity, NativeElementRelationSink sink);

    boolean readFirstElementChild(long elementIdentity, NativeElementRelationSink sink);

    boolean readLastElementChild(long elementIdentity, NativeElementRelationSink sink);

    boolean readPreviousElementSibling(long elementIdentity, NativeElementRelationSink sink);

    boolean readNextElementSibling(long elementIdentity, NativeElementRelationSink sink);

    int getChildElementCount(long elementIdentity);

    double getClientWidth(long elementIdentity);

    double getClientHeight(long elementIdentity);

    double getClientTop(long elementIdentity);

    double getClientLeft(long elementIdentity);

    double getScrollLeft(long elementIdentity);

    double getScrollTop(long elementIdentity);

    double getScrollWidth(long elementIdentity);

    double getScrollHeight(long elementIdentity);
}
