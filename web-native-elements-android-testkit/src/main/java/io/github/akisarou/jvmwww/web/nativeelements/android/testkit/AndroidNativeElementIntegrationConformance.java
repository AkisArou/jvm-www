package io.github.akisarou.jvmwww.web.nativeelements.android.testkit;

import android.os.Looper;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.geometry.DOMRect;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;
import io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost;

/** End-to-end owner/runtime integration for the Android committed-layout host. */
public final class AndroidNativeElementIntegrationConformance {
    private int passed;

    public static void main(String[] args) {
        Looper.prepare();
        new AndroidNativeElementIntegrationConformance().run();
    }

    private void run() {
        ManualOwnerExecutor owner = new ManualOwnerExecutor();
        RuntimeInstance runtime = new RuntimeInstance(owner, new CollectingErrorReporter());
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();

        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long identity = host.mountElement("RCTView", "card");
            host.commitLayout(identity, 10.25, 20.5, 30.75, 40.125, 1.0, 2.0, 10.5, -0.25);
            host.commitClientAndScrollMetrics(
                    identity,
                    21.5,
                    22.25,
                    -0.0,
                    1.75,
                    -3.5,
                    4.125,
                    300.5,
                    400.75);
            ReactNativeElement element = context.createElement(identity);

            yes(element.isConnected(), "mounted element connected");
            eq("RCTView", element.getTagName(), "tag through context");
            eq("card", element.getId(), "id through context");
            DOMRect rect = element.getBoundingClientRect();
            raw(10.25, rect.getX(), "transformed x");
            raw(20.5, rect.getY(), "transformed y");
            raw(30.75, rect.getWidth(), "transformed width");
            raw(40.125, rect.getHeight(), "transformed height");
            raw(11.0, element.getOffsetWidth(), "untransformed rounded width");
            raw(-0.0, element.getOffsetHeight(), "untransformed negative-zero height");
            raw(21.5, element.getClientWidth(), "client width");
            raw(22.25, element.getClientHeight(), "client height");
            raw(-0.0, element.getClientTop(), "client top");
            raw(1.75, element.getClientLeft(), "client left");
            raw(-3.5, element.getScrollLeft(), "scroll left");
            raw(4.125, element.getScrollTop(), "scroll top");
            raw(300.5, element.getScrollWidth(), "scroll width");
            raw(400.75, element.getScrollHeight(), "scroll height");

            host.commitLayout(identity, 100, 200, 300, 400, 5, 6, 7, 8);
            host.commitClientAndScrollMetrics(identity, 31,32,33,34,35,36,37,38);
            DOMRect later = element.getBoundingClientRect();
            raw(100.0, later.getX(), "later committed x");
            raw(10.25, rect.getX(), "earlier snapshot remains static");
            raw(38.0, element.getScrollHeight(), "later committed metrics");

            host.clearCommittedClientAndScrollMetrics(identity);
            raw(0.0, element.getClientWidth(), "cleared metric default");
            raw(100.0, element.getBoundingClientRect().getX(), "metric clear keeps layout");

            host.unmountElement(identity);
            no(element.isConnected(), "unmounted wrapper disconnected");
            eq("", element.getTagName(), "unmounted tag default");
            DOMRect empty = element.getBoundingClientRect();
            raw(0.0, empty.getX(), "unmounted x default");
            raw(0.0, empty.getWidth(), "unmounted width default");
            raw(0.0, element.getOffsetWidth(), "unmounted offset default");
            raw(0.0, element.getScrollHeight(), "unmounted scroll default");
            pass();
        } finally {
            runtime.leaveHostTurn();
        }

        runtime.close();
        host.close();
        System.out.println(
                "Android native element integration conformance: " + passed + " tests passed");
    }

    private void pass() { passed++; }
    private static void yes(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static void no(boolean value, String label) { yes(!value, label); }
    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
    private static void raw(double expected, double actual, String label) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
