package io.github.akisarou.jvmwww.web.nativeelements.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;

/** Deterministic Java 8 conformance for offset parent identity and relative positions. */
public final class NativeElementOffsetConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        new NativeElementOffsetConformance().run();
    }

    private void run() throws Exception {
        parentIdentityAndRounding();
        sinkContractAndReentry();
        cacheAndOwnership();
        System.out.println("Native element offset conformance: " + passed + " tests passed");
    }

    private void parentIdentityAndRounding() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long parentIdentity = Long.MIN_VALUE + 21L;
            long childIdentity = Long.MIN_VALUE + 22L;
            ReactNativeElement parent = context.createElement(parentIdentity);
            same(parent, context.createElement(parentIdentity), "stable parent wrapper");
            ReactNativeElement child = context.createElement(childIdentity);

            host.offsetAvailable = true;
            host.hasOffsetParent = true;
            host.offsetParentIdentity = parentIdentity;
            host.offsetTop = 10.5;
            host.offsetLeft = -0.25;
            same(parent, child.getOffsetParent(), "exact offset parent wrapper");
            raw(11.0, child.getOffsetTop(), "positive tie");
            raw(-0.0, child.getOffsetLeft(), "negative fraction");
            eq(childIdentity, host.identity, "child identity forwarded");

            host.offsetTop = -1.5;
            host.offsetLeft = Double.POSITIVE_INFINITY;
            raw(-1.0, child.getOffsetTop(), "negative tie");
            raw(Double.POSITIVE_INFINITY, child.getOffsetLeft(), "infinity");
            host.offsetLeft = Double.NaN;
            yes(Double.isNaN(child.getOffsetLeft()), "NaN");

            host.hasOffsetParent = false;
            host.offsetTop = 4.5;
            host.offsetLeft = -0.5;
            isNull(child.getOffsetParent(), "hidden root parent");
            raw(5.0, child.getOffsetTop(), "root-relative top");
            raw(-0.0, child.getOffsetLeft(), "root-relative left");

            host.hasOffsetParent = true;
            host.offsetParentIdentity = 909L;
            isNull(child.getOffsetParent(), "unpublished parent");
            raw(5.0, child.getOffsetTop(), "missing wrapper keeps position");

            host.offsetAvailable = false;
            isNull(child.getOffsetParent(), "unavailable parent");
            raw(0.0, child.getOffsetTop(), "unavailable top");
            raw(0.0, child.getOffsetLeft(), "unavailable left");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void sinkContractAndReentry() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        runtime.enterHostTurn();
        try {
            ReactNativeElement element = new NativeElementContext(runtime, host).createElement(10L);
            host.offsetMode = FakeNativeElementHost.NO_WRITE;
            rejectsOffset(element, "true without write");
            host.offsetMode = FakeNativeElementHost.FALSE_WRITE;
            rejectsOffset(element, "false after write");
            host.offsetMode = FakeNativeElementHost.TWICE;
            rejectsOffset(element, "double write");
            host.offsetMode = FakeNativeElementHost.THROW;
            try {
                element.getOffsetTop();
                throw new AssertionError("host exception missing");
            } catch (FakeNativeElementHost.Marker expected) {
                // Exact host exception escapes.
            }

            host.offsetMode = FakeNativeElementHost.NORMAL;
            host.offsetAvailable = true;
            host.retainOffset = true;
            host.offsetTop = 7.25;
            raw(7.0, element.getOffsetTop(), "offset recovery");
            try {
                host.retainedOffset.setOffset(false, 0L, 1.0, 2.0);
                throw new AssertionError("retained offset sink accepted");
            } catch (IllegalStateException expected) {
                // Sink lifetime is one synchronous host call.
            }

            host.reentrant = element;
            host.mode = FakeNativeElementHost.REENTER;
            rejectsRect(element, "rectangle-to-offset reentry");
            host.mode = FakeNativeElementHost.NORMAL;
            host.offsetMode = FakeNativeElementHost.REENTER;
            rejectsOffset(element, "offset-to-rectangle reentry");
            host.offsetMode = FakeNativeElementHost.NORMAL;
            host.reentrant = null;
            host.available = true;
            host.transformedHeight = 44.0;
            host.offsetLeft = 8.5;
            raw(44.0, element.getBoundingClientRect().getHeight(), "rectangle recovery");
            raw(9.0, element.getOffsetLeft(), "offset reentry recovery");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void cacheAndOwnership() throws Exception {
        final RuntimeInstance runtime = runtime();
        final FakeNativeElementHost host = new FakeNativeElementHost();
        try {
            new NativeElementContext(runtime, host);
            throw new AssertionError("idle construction accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }

        final ReactNativeElement[] element = new ReactNativeElement[1];
        runtime.enterHostTurn();
        try {
            NativeElementContext first = new NativeElementContext(runtime, host);
            element[0] = first.createElement(91L);
            same(element[0], first.createElement(91L), "cache hit");
            NativeElementContext second = new NativeElementContext(runtime, host);
            try {
                second.createElement(91L);
                throw new AssertionError("cross-context wrapper accepted");
            } catch (IllegalStateException expected) {
                // One renderer host belongs to one context/runtime pair.
            }
        } finally {
            runtime.leaveHostTurn();
        }

        try {
            element[0].getOffsetParent();
            throw new AssertionError("idle access accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        int before = host.calls;
        final Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    element[0].getOffsetTop();
                } catch (Throwable error) {
                    failure[0] = error;
                }
            }
        });
        thread.start();
        thread.join();
        yes(failure[0] instanceof IllegalStateException, "foreign access");
        eq(before, host.calls, "foreign check before host");
        runtime.close();
        before = host.calls;
        try {
            element[0].getOffsetLeft();
            throw new AssertionError("closed access accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        eq(before, host.calls, "closed check before host");
        passed++;
    }

    private static RuntimeInstance runtime() {
        return new RuntimeInstance(new ManualOwnerExecutor(), new CollectingErrorReporter());
    }

    private static void closeTurn(RuntimeInstance runtime) {
        runtime.leaveHostTurn();
        runtime.close();
    }

    private static void rejectsOffset(ReactNativeElement element, String label) {
        try {
            element.getOffsetTop();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(label + " accepted");
    }

    private static void rejectsRect(ReactNativeElement element, String label) {
        try {
            element.getBoundingClientRect();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(label + " accepted");
    }

    private static void yes(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void same(Object expected, Object actual, String label) {
        if (expected != actual) throw new AssertionError(label);
    }

    private static void isNull(Object value, String label) {
        if (value != null) throw new AssertionError(label + ": got " + value);
    }

    private static void eq(long expected, long actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + actual);
    }

    private static void raw(double expected, double actual, String label) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
            throw new AssertionError(label + ": " + actual);
        }
    }
}
