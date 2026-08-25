package io.github.akisarou.jvmwww.web.nativeelements.android.testkit;

import android.os.Looper;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;
import io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost;
import java.util.concurrent.atomic.AtomicReference;

/** Java 8 conformance for Android public-wrapper and committed offset storage. */
public final class AndroidNativeElementOffsetConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        new AndroidNativeElementOffsetConformance().run();
    }

    private void run() throws Exception {
        publicIdentityAndRootOffsets();
        generationSafetyAndGrowth();
        independentAvailabilityAndValidation();
        ownerAndClose();
        System.out.println("Android native element offset conformance: " + passed + " tests passed");
    }

    private void publicIdentityAndRootOffsets() {
        RuntimeInstance runtime = new RuntimeInstance(
                new ManualOwnerExecutor(), new CollectingErrorReporter());
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long parentIdentity = host.mountElement("RCTView", "parent");
            long childIdentity = host.mountElement("RCTView", "child");
            ReactNativeElement parent = context.createElement(parentIdentity);
            same(parent, context.createElement(parentIdentity), "stable public wrapper");
            ReactNativeElement child = context.createElement(childIdentity);
            same(parent, host.getPublicInstance(parentIdentity), "host cache identity");

            yes(host.commitOffset(parentIdentity, 0L, 5.5, -0.25), "root offset");
            yes(host.commitOffset(childIdentity, parentIdentity, 10.5, -1.5), "child offset");
            same(parent, child.getOffsetParent(), "offset parent wrapper");
            raw(11.0, child.getOffsetTop(), "child top rounded");
            raw(-1.0, child.getOffsetLeft(), "child left rounded");
            isNull(parent.getOffsetParent(), "root parent hidden");
            raw(6.0, parent.getOffsetTop(), "root top available");
            raw(-0.0, parent.getOffsetLeft(), "root left available");

            RecordingOffsetSink sink = new RecordingOffsetSink();
            yes(host.measureOffset(childIdentity, sink), "direct offset available");
            sink.assertOffset(true, parentIdentity, 10.5, -1.5, "direct child offset");
            pass();
        } finally {
            runtime.leaveHostTurn();
            runtime.close();
            host.close();
        }
    }

    private void generationSafetyAndGrowth() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long parent = host.mountElement("View", "old");
        long child = host.mountElement("View", "child");
        yes(host.commitOffset(child, parent, 1, 2), "initial relation");
        yes(host.unmountElement(parent), "parent unmounted");
        RecordingOffsetSink sink = new RecordingOffsetSink();
        no(host.measureOffset(child, sink), "stale parent unavailable");
        eq(0, sink.writes, "stale parent writes nothing");

        long replacement = host.mountElement("View", "new");
        yes(parent != replacement, "slot generation changes identity");
        no(host.measureOffset(child, sink), "old identity does not alias replacement");
        yes(host.commitOffset(child, replacement, 3, 4), "replacement relation");
        yes(host.measureOffset(child, sink), "replacement available");
        sink.assertOffset(true, replacement, 3, 4, "replacement offset");

        long current = replacement;
        for (int iteration = 0; iteration < 10000; iteration++) {
            yes(host.unmountElement(current), "generation unmount " + iteration);
            long next = host.mountElement("View", "cycle");
            yes(next != current, "generation identity " + iteration);
            no(host.isConnected(current), "generation stale " + iteration);
            current = next;
        }

        long[] identities = new long[128];
        for (int index = 0; index < identities.length; index++) {
            identities[index] = host.mountElement("Cell", Integer.toString(index));
            yes(host.commitOffset(identities[index], 0L, index + 0.25, index + 0.75),
                    "growth offset " + index);
        }
        sink.reset();
        yes(host.measureOffset(identities[127], sink), "grown tail resolves");
        sink.assertOffset(false, 0L, 127.25, 127.75, "grown tail");
        host.close();
        pass();
    }

    private void independentAvailabilityAndValidation() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long parent = host.mountElement("View", "parent");
        long child = host.mountElement("View", "child");
        RecordingOffsetSink sink = new RecordingOffsetSink();
        no(host.measureOffset(child, sink), "new mount has no offset");
        yes(host.commitLayout(child, 1,2,3,4,5,6,7,8), "layout committed");
        yes(host.commitClientAndScrollMetrics(child, 9,10,11,12,13,14,15,16),
                "metrics committed");
        yes(host.commitOffset(child, parent, Double.NaN, Double.NEGATIVE_INFINITY),
                "unrestricted offset committed");
        yes(host.measureOffset(child, sink), "unrestricted offset available");
        sink.assertOffset(true, parent, Double.NaN, Double.NEGATIVE_INFINITY, "unrestricted");

        no(host.commitOffset(Long.MAX_VALUE, parent, 0, 0), "stale child rejected");
        no(host.commitOffset(child, Long.MAX_VALUE, 0, 0), "stale parent rejected");
        no(host.commitOffset(child, child, 0, 0), "self parent rejected");
        no(host.clearCommittedOffset(Long.MAX_VALUE), "stale clear rejected");
        yes(host.clearCommittedOffset(child), "offset cleared");
        sink.reset();
        no(host.measureOffset(child, sink), "cleared offset unavailable");
        raw(9.0, host.getClientWidth(child), "offset clear keeps metrics");
        yes(host.measureBoundingClientRect(child, true, new RectSink()),
                "offset clear keeps rectangle");
        yes(host.isConnected(child), "offset clear keeps connection");
        host.close();
        pass();
    }

    private void ownerAndClose() throws Exception {
        final AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        final long identity = host.mountElement("View", "owner");
        host.commitOffset(identity, 0L, 1, 2);
        final AtomicReference<Throwable> readFailure = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> writeFailure = new AtomicReference<Throwable>();
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                Looper.prepare();
                try {
                    host.measureOffset(identity, new RecordingOffsetSink());
                } catch (Throwable error) {
                    readFailure.set(error);
                }
                try {
                    host.commitOffset(identity, 0L, 9, 9);
                } catch (Throwable error) {
                    writeFailure.set(error);
                }
            }
        });
        thread.start();
        thread.join();
        instanceOf(IllegalStateException.class, readFailure.get(), "foreign read refused");
        instanceOf(IllegalStateException.class, writeFailure.get(), "foreign write refused");
        RecordingOffsetSink sink = new RecordingOffsetSink();
        yes(host.measureOffset(identity, sink), "owner offset retained");
        sink.assertOffset(false, 0L, 1, 2, "foreign write made no change");

        host.close();
        yes(host.isClosed(), "closed");
        isNull(host.getPublicInstance(identity), "wrapper table released");
        sink.reset();
        no(host.measureOffset(identity, sink), "closed offset unavailable");
        eq(0, sink.writes, "closed offset writes nothing");
        try {
            host.commitOffset(identity, 0L, 0, 0);
            throw new AssertionError("closed mutation accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        host.close();
        pass();
    }

    private void pass() {
        passed++;
    }

    private static final class RecordingOffsetSink implements NativeElementOffsetSink {
        boolean hasParent;
        long parent;
        double top;
        double left;
        int writes;

        @Override
        public void setOffset(boolean hasParent, long parent, double top, double left) {
            this.hasParent = hasParent;
            this.parent = parent;
            this.top = top;
            this.left = left;
            writes++;
        }

        void reset() {
            hasParent = false;
            parent = 0L;
            top = 0.0;
            left = 0.0;
            writes = 0;
        }

        void assertOffset(
                boolean expectedHasParent,
                long expectedParent,
                double expectedTop,
                double expectedLeft,
                String label) {
            eq(1, writes, label + " writes");
            if (hasParent != expectedHasParent) throw new AssertionError(label + " parent flag");
            if (expectedHasParent) eq(expectedParent, parent, label + " parent");
            raw(expectedTop, top, label + " top");
            raw(expectedLeft, left, label + " left");
        }
    }

    private static final class RectSink
            implements io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink {
        @Override public void setRect(double x, double y, double width, double height) {}
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

    private static void isNull(Object value, String label) {
        if (value != null) throw new AssertionError(label + ": got " + value);
    }

    private static void instanceOf(Class<?> type, Throwable value, String label) {
        if (!type.isInstance(value)) throw new AssertionError(label + ": got " + value);
    }

    private static void eq(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + actual);
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
