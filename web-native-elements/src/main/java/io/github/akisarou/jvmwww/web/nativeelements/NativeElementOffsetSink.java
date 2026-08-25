package io.github.akisarou.jvmwww.web.nativeelements;

/** Reusable primitive sink for one synchronous HTMLElement offset snapshot. */
public interface NativeElementOffsetSink {
    /**
     * Publishes one offset snapshot.
     *
     * @param hasOffsetParent whether the parent is exposed as a public element
     * @param offsetParentIdentity opaque parent identity; ignored when {@code hasOffsetParent} is false
     * @param top unrounded offset relative to the parent
     * @param left unrounded offset relative to the parent
     */
    void setOffset(
            boolean hasOffsetParent,
            long offsetParentIdentity,
            double top,
            double left);
}
