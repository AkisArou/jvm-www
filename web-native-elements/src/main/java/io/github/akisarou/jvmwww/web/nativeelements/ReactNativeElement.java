package io.github.akisarou.jvmwww.web.nativeelements;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.geometry.DOMRect;

/**
 * Read-only renderer-owned element value for the selected React Native element-node profile.
 *
 * <p>The renderer creates and caches wrappers through {@link NativeElementContext}. Tree mutation,
 * Android view access, asynchronous legacy measurement, focus, pointer capture, and document
 * traversal are separate capability slices.</p>
 */
public final class ReactNativeElement {
    public static final int ELEMENT_NODE = 1;

    private final NativeElementContext context;
    private final long elementIdentity;

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
}
