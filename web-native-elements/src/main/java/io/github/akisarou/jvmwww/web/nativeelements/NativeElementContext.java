package io.github.akisarou.jvmwww.web.nativeelements;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.geometry.DOMRect;
import java.util.Objects;

/**
 * Shared owner-confined renderer context and reusable primitive measurement sinks.
 *
 * <p>One context is retained by every element wrapper belonging to a renderer/runtime pair. The
 * context itself is reused as the host's rectangle and offset sink, avoiding a callback or tuple
 * allocation for each multi-value layout read. Scalar client and scroll properties delegate
 * directly through the primitive identity and allocate nothing.</p>
 */
public final class NativeElementContext
        implements NativeElementRectSink, NativeElementOffsetSink {
    private static final byte READ_NONE = 0;
    private static final byte READ_RECT = 1;
    private static final byte READ_OFFSET = 2;

    private final RuntimeInstance runtime;
    private final NativeElementHost host;

    private double measuredX;
    private double measuredY;
    private double measuredWidth;
    private double measuredHeight;
    private long measuredOffsetParentIdentity;
    private double measuredOffsetTop;
    private double measuredOffsetLeft;
    private boolean measuredHasOffsetParent;
    private byte activeReadKind;
    private boolean readWritten;

    public NativeElementContext(RuntimeInstance runtime, NativeElementHost host) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        NativeElementRuntimeChecks.assertLanguageExecution(this.runtime);
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Returns the renderer-owned stable public wrapper for one exact identity, creating it once.
     */
    public ReactNativeElement createElement(long elementIdentity) {
        assertAccess();
        ReactNativeElement existing = host.getPublicInstance(elementIdentity);
        if (existing != null) {
            assertPublicInstance(existing, elementIdentity);
            return existing;
        }

        ReactNativeElement created = new ReactNativeElement(this, elementIdentity);
        ReactNativeElement registered = host.registerPublicInstance(elementIdentity, created);
        if (registered != created) {
            throw new IllegalStateException(
                    "NativeElementHost did not retain the exact registered public instance");
        }
        return created;
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    @Override
    public void setRect(double x, double y, double width, double height) {
        if (activeReadKind != READ_RECT) {
            throw new IllegalStateException("NativeElementRectSink used outside a rectangle read");
        }
        if (readWritten) {
            throw new IllegalStateException("NativeElementHost wrote more than one read result");
        }
        measuredX = x;
        measuredY = y;
        measuredWidth = width;
        measuredHeight = height;
        readWritten = true;
    }

    @Override
    public void setOffset(
            boolean hasOffsetParent,
            long offsetParentIdentity,
            double top,
            double left) {
        if (activeReadKind != READ_OFFSET) {
            throw new IllegalStateException("NativeElementOffsetSink used outside an offset read");
        }
        if (readWritten) {
            throw new IllegalStateException("NativeElementHost wrote more than one read result");
        }
        measuredHasOffsetParent = hasOffsetParent;
        measuredOffsetParentIdentity = hasOffsetParent ? offsetParentIdentity : 0L;
        measuredOffsetTop = top;
        measuredOffsetLeft = left;
        readWritten = true;
    }

    boolean isConnected(long elementIdentity) {
        assertAccess();
        return host.isConnected(elementIdentity);
    }

    String getTagName(long elementIdentity) {
        assertAccess();
        String value = host.getTagName(elementIdentity);
        return value == null ? "" : value;
    }

    String getId(long elementIdentity) {
        assertAccess();
        String value = host.getId(elementIdentity);
        return value == null ? "" : value;
    }

    DOMRect getBoundingClientRect(long elementIdentity) {
        measureRect(elementIdentity, true);
        return new DOMRect(measuredX, measuredY, measuredWidth, measuredHeight);
    }

    double getOffsetWidth(long elementIdentity) {
        return measureRect(elementIdentity, false) ? roundLikeEcmaScript(measuredWidth) : 0.0;
    }

    double getOffsetHeight(long elementIdentity) {
        return measureRect(elementIdentity, false) ? roundLikeEcmaScript(measuredHeight) : 0.0;
    }

    ReactNativeElement getOffsetParent(long elementIdentity) {
        if (!measureOffset(elementIdentity) || !measuredHasOffsetParent) {
            return null;
        }
        long parentIdentity = measuredOffsetParentIdentity;
        ReactNativeElement parent = host.getPublicInstance(parentIdentity);
        if (parent == null) {
            return null;
        }
        assertPublicInstance(parent, parentIdentity);
        return parent;
    }

    double getOffsetTop(long elementIdentity) {
        return measureOffset(elementIdentity) ? roundLikeEcmaScript(measuredOffsetTop) : 0.0;
    }

    double getOffsetLeft(long elementIdentity) {
        return measureOffset(elementIdentity) ? roundLikeEcmaScript(measuredOffsetLeft) : 0.0;
    }

    double getClientWidth(long elementIdentity) {
        assertAccess();
        return host.getClientWidth(elementIdentity);
    }

    double getClientHeight(long elementIdentity) {
        assertAccess();
        return host.getClientHeight(elementIdentity);
    }

    double getClientTop(long elementIdentity) {
        assertAccess();
        return host.getClientTop(elementIdentity);
    }

    double getClientLeft(long elementIdentity) {
        assertAccess();
        return host.getClientLeft(elementIdentity);
    }

    double getScrollLeft(long elementIdentity) {
        assertAccess();
        return host.getScrollLeft(elementIdentity);
    }

    double getScrollTop(long elementIdentity) {
        assertAccess();
        return host.getScrollTop(elementIdentity);
    }

    double getScrollWidth(long elementIdentity) {
        assertAccess();
        return host.getScrollWidth(elementIdentity);
    }

    double getScrollHeight(long elementIdentity) {
        assertAccess();
        return host.getScrollHeight(elementIdentity);
    }

    private boolean measureRect(long elementIdentity, boolean includeTransform) {
        beginRead(READ_RECT);
        try {
            boolean available = host.measureBoundingClientRect(
                    elementIdentity,
                    includeTransform,
                    this);
            validateReadResult(available);
            if (!available) {
                measuredX = 0.0;
                measuredY = 0.0;
                measuredWidth = 0.0;
                measuredHeight = 0.0;
            }
            return available;
        } finally {
            endRead();
        }
    }

    private boolean measureOffset(long elementIdentity) {
        beginRead(READ_OFFSET);
        try {
            boolean available = host.measureOffset(elementIdentity, this);
            validateReadResult(available);
            if (!available) {
                measuredHasOffsetParent = false;
                measuredOffsetParentIdentity = 0L;
                measuredOffsetTop = 0.0;
                measuredOffsetLeft = 0.0;
            }
            return available;
        } finally {
            endRead();
        }
    }

    private void beginRead(byte readKind) {
        assertAccess();
        if (activeReadKind != READ_NONE) {
            throw new IllegalStateException("NativeElementHost re-entered element measurement");
        }
        activeReadKind = readKind;
        readWritten = false;
    }

    private void validateReadResult(boolean available) {
        if (available != readWritten) {
            throw new IllegalStateException(
                    available
                            ? "NativeElementHost returned true without writing a result"
                            : "NativeElementHost wrote a result before returning false");
        }
    }

    private void endRead() {
        activeReadKind = READ_NONE;
        readWritten = false;
    }

    private void assertPublicInstance(ReactNativeElement value, long elementIdentity) {
        if (!value.matches(this, elementIdentity)) {
            throw new IllegalStateException(
                    "NativeElementHost returned a public instance for another context or identity");
        }
    }

    void assertAccess() {
        NativeElementRuntimeChecks.assertLanguageExecution(runtime);
    }

    /** ECMAScript Math.round over one primitive layout value, including negative zero. */
    private static double roundLikeEcmaScript(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) {
            return value;
        }
        if (value >= -0.5 && value < 0.0) {
            return -0.0;
        }
        double floor = Math.floor(value);
        return value - floor < 0.5 ? floor : floor + 1.0;
    }
}
