package io.github.akisarou.jvmwww.web.nativeelements;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.geometry.DOMRect;
import java.util.Objects;

/**
 * Shared owner-confined renderer context and reusable primitive rectangle sink.
 *
 * <p>One context is retained by every element wrapper belonging to a renderer/runtime pair. The
 * context itself is reused as the host's measurement sink, avoiding a callback or tuple allocation
 * for each multi-value layout read. Scalar client and scroll properties delegate directly through
 * the primitive identity and allocate nothing.</p>
 */
public final class NativeElementContext implements NativeElementRectSink {
    private final RuntimeInstance runtime;
    private final NativeElementHost host;

    private double measuredX;
    private double measuredY;
    private double measuredWidth;
    private double measuredHeight;
    private boolean measurementActive;
    private boolean measurementWritten;

    public NativeElementContext(RuntimeInstance runtime, NativeElementHost host) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        NativeElementRuntimeChecks.assertLanguageExecution(this.runtime);
        this.host = Objects.requireNonNull(host, "host");
    }

    /** Creates one renderer-owned public element wrapper. The renderer owns wrapper identity reuse. */
    public ReactNativeElement createElement(long elementIdentity) {
        assertAccess();
        return new ReactNativeElement(this, elementIdentity);
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    @Override
    public void setRect(double x, double y, double width, double height) {
        if (!measurementActive) {
            throw new IllegalStateException("NativeElementRectSink used outside a measurement");
        }
        if (measurementWritten) {
            throw new IllegalStateException("NativeElementHost wrote more than one rectangle");
        }
        measuredX = x;
        measuredY = y;
        measuredWidth = width;
        measuredHeight = height;
        measurementWritten = true;
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
        measure(elementIdentity, true);
        return new DOMRect(measuredX, measuredY, measuredWidth, measuredHeight);
    }

    double getOffsetWidth(long elementIdentity) {
        return measure(elementIdentity, false) ? roundLikeEcmaScript(measuredWidth) : 0.0;
    }

    double getOffsetHeight(long elementIdentity) {
        return measure(elementIdentity, false) ? roundLikeEcmaScript(measuredHeight) : 0.0;
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

    private boolean measure(long elementIdentity, boolean includeTransform) {
        assertAccess();
        if (measurementActive) {
            throw new IllegalStateException("NativeElementHost re-entered element measurement");
        }
        measurementActive = true;
        measurementWritten = false;
        try {
            boolean available = host.measureBoundingClientRect(
                    elementIdentity,
                    includeTransform,
                    this);
            if (available != measurementWritten) {
                throw new IllegalStateException(
                        available
                                ? "NativeElementHost returned true without writing a rectangle"
                                : "NativeElementHost wrote a rectangle before returning false");
            }
            if (!available) {
                measuredX = 0.0;
                measuredY = 0.0;
                measuredWidth = 0.0;
                measuredHeight = 0.0;
            }
            return available;
        } finally {
            measurementActive = false;
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
