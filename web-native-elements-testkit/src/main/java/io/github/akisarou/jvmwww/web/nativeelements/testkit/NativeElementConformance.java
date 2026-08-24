package io.github.akisarou.jvmwww.web.nativeelements.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.geometry.DOMRect;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;

/** Deterministic Java 8 conformance for the first renderer-owned element profile. */
public final class NativeElementConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        new NativeElementConformance().run();
    }

    private void run() throws Exception {
        metadata();
        bounds();
        offsets();
        detached();
        malformedHost();
        reentry();
        ownership();
        System.out.println("Native element conformance: " + passed + " tests passed");
    }

    private void metadata() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        host.connected = true;
        host.tag = "RN:View";
        host.id = "hero";
        runtime.enterHostTurn();
        try {
            ReactNativeElement element =
                    new NativeElementContext(runtime, host).createElement(Long.MIN_VALUE + 17L);
            same(runtime, element.getRuntime(), "runtime");
            yes(element.isConnected(), "connected");
            eq("hero", element.getId(), "id");
            eq("RN:View", element.getTagName(), "tag");
            eq("RN:View", element.getNodeName(), "node name");
            eq(ReactNativeElement.ELEMENT_NODE, element.getNodeType(), "node type");
            same(null, element.getNodeValue(), "node value");
            eq(Long.MIN_VALUE + 17L, host.identity, "opaque identity");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void bounds() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        host.available = true;
        host.x = 10.25;
        host.y = -5.5;
        host.transformedWidth = 40.75;
        host.transformedHeight = 80.125;
        runtime.enterHostTurn();
        try {
            ReactNativeElement element = new NativeElementContext(runtime, host).createElement(7L);
            DOMRect first = element.getBoundingClientRect();
            rect(first, 10.25, -5.5, 40.75, 80.125, "first");
            yes(host.includeTransform, "bounds include transforms");
            host.x = 99.0;
            DOMRect second = element.getBoundingClientRect();
            notSame(first, second, "static result identity");
            eq(10.25, first.getX(), "first remains static");
            eq(99.0, second.getX(), "current layout");
            first.setX(-100.0);
            eq(99.0, element.getBoundingClientRect().getX(), "caller isolation");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void offsets() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        host.available = true;
        host.transformedWidth = 900.0;
        host.transformedHeight = 800.0;
        host.width = 10.5;
        host.height = 20.49;
        runtime.enterHostTurn();
        try {
            ReactNativeElement element = new NativeElementContext(runtime, host).createElement(8L);
            eq(11.0, element.getOffsetWidth(), "positive tie");
            no(host.includeTransform, "width excludes transforms");
            eq(20.0, element.getOffsetHeight(), "fraction down");
            no(host.includeTransform, "height excludes transforms");
            host.width = -0.25;
            raw(-0.0, element.getOffsetWidth(), "negative fraction");
            host.width = -0.5;
            raw(-0.0, element.getOffsetWidth(), "negative half");
            host.width = -1.5;
            eq(-1.0, element.getOffsetWidth(), "negative tie");
            host.width = -0.5000000000000001;
            eq(-1.0, element.getOffsetWidth(), "below half");
            host.width = -0.0;
            raw(-0.0, element.getOffsetWidth(), "negative zero");
            host.width = Double.NaN;
            yes(Double.isNaN(element.getOffsetWidth()), "NaN");
            host.width = Double.POSITIVE_INFINITY;
            eq(Double.POSITIVE_INFINITY, element.getOffsetWidth(), "infinity");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void detached() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        host.tag = null;
        host.id = null;
        runtime.enterHostTurn();
        try {
            ReactNativeElement element = new NativeElementContext(runtime, host).createElement(9L);
            no(element.isConnected(), "detached");
            eq("", element.getId(), "empty id");
            eq("", element.getTagName(), "empty tag");
            DOMRect first = element.getBoundingClientRect();
            DOMRect second = element.getBoundingClientRect();
            notSame(first, second, "static detached results");
            rect(first, 0.0, 0.0, 0.0, 0.0, "detached rect");
            eq(0.0, element.getOffsetWidth(), "detached width");
            eq(0.0, element.getOffsetHeight(), "detached height");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void malformedHost() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        runtime.enterHostTurn();
        try {
            ReactNativeElement element = new NativeElementContext(runtime, host).createElement(10L);
            host.mode = FakeNativeElementHost.NO_WRITE;
            rejects(element, "true without write");
            host.mode = FakeNativeElementHost.FALSE_WRITE;
            rejects(element, "false after write");
            host.mode = FakeNativeElementHost.TWICE;
            rejects(element, "double write");
            host.mode = FakeNativeElementHost.THROW;
            try {
                element.getBoundingClientRect();
                throw new AssertionError("host exception missing");
            } catch (FakeNativeElementHost.Marker expected) {
                // Exact host exception escapes.
            }
            host.mode = FakeNativeElementHost.NORMAL;
            host.available = true;
            host.retain = true;
            host.transformedWidth = 12.0;
            eq(12.0, element.getBoundingClientRect().getWidth(), "recovery");
            try {
                host.retained.setRect(1.0, 2.0, 3.0, 4.0);
                throw new AssertionError("retained sink accepted");
            } catch (IllegalStateException expected) {
                // Sink lifetime is one synchronous host call.
            }
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void reentry() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        runtime.enterHostTurn();
        try {
            ReactNativeElement element = new NativeElementContext(runtime, host).createElement(11L);
            host.reentrant = element;
            host.mode = FakeNativeElementHost.REENTER;
            rejects(element, "reentry");
            host.mode = FakeNativeElementHost.NORMAL;
            host.reentrant = null;
            host.available = true;
            host.transformedHeight = 44.0;
            eq(44.0, element.getBoundingClientRect().getHeight(), "reentry recovery");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void ownership() throws Exception {
        final RuntimeInstance runtime = runtime();
        final FakeNativeElementHost host = new FakeNativeElementHost();
        try {
            new NativeElementContext(runtime, host);
            throw new AssertionError("idle construction accepted");
        } catch (IllegalStateException expected) {
            // expected
        }
        final ReactNativeElement[] element = new ReactNativeElement[1];
        runtime.enterHostTurn();
        try {
            element[0] = new NativeElementContext(runtime, host).createElement(12L);
        } finally {
            runtime.leaveHostTurn();
        }
        try {
            element[0].getNodeType();
            throw new AssertionError("idle access accepted");
        } catch (IllegalStateException expected) {
            // expected
        }
        int before = host.calls;
        final Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    element[0].isConnected();
                } catch (Throwable error) {
                    failure[0] = error;
                }
            }
        });
        thread.start();
        thread.join();
        yes(failure[0] instanceof IllegalStateException, "foreign access");
        eq(before, host.calls, "foreign check precedes host");
        runtime.close();
        before = host.calls;
        try {
            element[0].isConnected();
            throw new AssertionError("closed access accepted");
        } catch (IllegalStateException expected) {
            // expected
        }
        eq(before, host.calls, "closed check precedes host");
        passed++;
    }

    private static RuntimeInstance runtime() {
        return new RuntimeInstance(new ManualOwnerExecutor(), new CollectingErrorReporter());
    }

    private static void closeTurn(RuntimeInstance runtime) {
        runtime.leaveHostTurn();
        runtime.close();
    }

    private static void rejects(ReactNativeElement element, String label) {
        try {
            element.getBoundingClientRect();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(label + " accepted");
    }

    private static void rect(
            DOMRect actual, double x, double y, double width, double height, String label) {
        eq(x, actual.getX(), label + " x");
        eq(y, actual.getY(), label + " y");
        eq(width, actual.getWidth(), label + " width");
        eq(height, actual.getHeight(), label + " height");
    }

    private static void yes(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void no(boolean value, String label) {
        yes(!value, label);
    }

    private static void same(Object expected, Object actual, String label) {
        if (expected != actual) throw new AssertionError(label);
    }

    private static void notSame(Object expected, Object actual, String label) {
        if (expected == actual) throw new AssertionError(label);
    }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label);
    }

    private static void eq(long expected, long actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + actual);
    }

    private static void eq(double expected, double actual, String label) {
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
            throw new AssertionError(label + ": " + actual);
        }
    }

    private static void raw(double expected, double actual, String label) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
            throw new AssertionError(label);
        }
    }

}
