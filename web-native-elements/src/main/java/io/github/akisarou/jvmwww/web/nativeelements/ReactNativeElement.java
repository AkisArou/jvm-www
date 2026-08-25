package io.github.akisarou.jvmwww.web.nativeelements;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.geometry.DOMRect;

/**
 * Read-only renderer-owned element value for the selected React Native element-node profile.
 *
 * <p>The renderer creates and caches wrappers through {@link NativeElementContext}. Tree mutation,
 * unsupported node kinds, Android view access, asynchronous legacy measurement, focus, pointer
 * capture, and document traversal are separate capability slices.</p>
 */
public final class ReactNativeElement {
    public static final int ELEMENT_NODE = 1;

    private final NativeElementContext context;
    private final long elementIdentity;
    private HTMLCollection children;

    ReactNativeElement(NativeElementContext context, long elementIdentity) {
        this.context = context;
        this.elementIdentity = elementIdentity;
    }

    public RuntimeInstance getRuntime() {
        return context.getRuntime();
    }

    public boolean isConnected() {
        return context.isConnected(elementIdentity);
    }

    public String getId() {
        return context.getId(elementIdentity);
    }

    public String getTagName() {
        return context.getTagName(elementIdentity);
    }

    public String getNodeName() {
        return getTagName();
    }

    public int getNodeType() {
        context.assertAccess();
        return ELEMENT_NODE;
    }

    public String getNodeValue() {
        context.assertAccess();
        return null;
    }

    /** Returns true when {@code other} is this exact public node object. */
    public boolean isSameNode(ReactNativeElement other) {
        context.assertAccess();
        return this == other;
    }

    /**
     * Returns whether {@code other} is an inclusive element descendant of this element.
     *
     * <p>Null and elements from another renderer context return false. The current primitive parent
     * relation is followed without allocating ancestor collections or intermediate wrappers.</p>
     */
    public boolean contains(ReactNativeElement other) {
        if (other == null) {
            return context.contains(elementIdentity, null, 0L);
        }
        return context.contains(elementIdentity, other.context, other.elementIdentity);
    }

    /** Returns the stable public wrapper for the current parent element. */
    public ReactNativeElement getParentElement() {
        return context.getParentElement(elementIdentity);
    }

    /** Returns the stable public wrapper for the first current element child. */
    public ReactNativeElement getFirstElementChild() {
        return context.getFirstElementChild(elementIdentity);
    }

    /** Returns the stable public wrapper for the last current element child. */
    public ReactNativeElement getLastElementChild() {
        return context.getLastElementChild(elementIdentity);
    }

    /** Returns the stable public wrapper for the previous current element sibling. */
    public ReactNativeElement getPreviousElementSibling() {
        return context.getPreviousElementSibling(elementIdentity);
    }

    /** Returns the stable public wrapper for the next current element sibling. */
    public ReactNativeElement getNextElementSibling() {
        return context.getNextElementSibling(elementIdentity);
    }

    /** Returns the current number of element children in the renderer snapshot. */
    public int getChildElementCount() {
        return context.getChildElementCount(elementIdentity);
    }

    /**
     * Returns this element's live same-object direct-element collection.
     *
     * <p>The collection is allocated only on first access and then retained by this already-required
     * public wrapper. It does not snapshot or retain child wrappers.</p>
     */
    public HTMLCollection getChildren() {
        context.assertAccess();
        HTMLCollection current = children;
        if (current == null) {
            current = new HTMLCollection(context, elementIdentity);
            children = current;
        }
        return current;
    }

    /** Returns one static transformed border-box snapshot. */
    public DOMRect getBoundingClientRect() {
        return context.getBoundingClientRect(elementIdentity);
    }

    /** Returns the untransformed border-box width using React Native's Math.round behavior. */
    public double getOffsetWidth() {
        return context.getOffsetWidth(elementIdentity);
    }

    /** Returns the untransformed border-box height using React Native's Math.round behavior. */
    public double getOffsetHeight() {
        return context.getOffsetHeight(elementIdentity);
    }

    /** Returns the renderer's stable public wrapper for the current offset parent. */
    public ReactNativeElement getOffsetParent() {
        return context.getOffsetParent(elementIdentity);
    }

    /** Returns the rounded untransformed offset relative to {@link #getOffsetParent()}. */
    public double getOffsetTop() {
        return context.getOffsetTop(elementIdentity);
    }

    /** Returns the rounded untransformed offset relative to {@link #getOffsetParent()}. */
    public double getOffsetLeft() {
        return context.getOffsetLeft(elementIdentity);
    }

    public double getClientWidth() {
        return context.getClientWidth(elementIdentity);
    }

    public double getClientHeight() {
        return context.getClientHeight(elementIdentity);
    }

    public double getClientTop() {
        return context.getClientTop(elementIdentity);
    }

    public double getClientLeft() {
        return context.getClientLeft(elementIdentity);
    }

    public double getScrollLeft() {
        return context.getScrollLeft(elementIdentity);
    }

    public double getScrollTop() {
        return context.getScrollTop(elementIdentity);
    }

    public double getScrollWidth() {
        return context.getScrollWidth(elementIdentity);
    }

    public double getScrollHeight() {
        return context.getScrollHeight(elementIdentity);
    }

    boolean matches(NativeElementContext expectedContext, long expectedIdentity) {
        return context == expectedContext && elementIdentity == expectedIdentity;
    }
}
