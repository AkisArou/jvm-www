package io.github.akisarou.jvmwww.web.nativeelements.android.testkit;

import android.os.Looper;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;
import io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic Java 8 conformance for committed element-only renderer relations. */
public final class AndroidNativeElementRelationConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        new AndroidNativeElementRelationConformance().run();
    }

    private void run() throws Exception {
        stableRelationsAndLazyWrappers();
        rootDefaultsAndGenerationSafety();
        validationAndSnapshotIsolation();
        growthOwnershipAndClose();
        System.out.println(
                "Android native element relation conformance: " + passed + " tests passed");
    }

    private void stableRelationsAndLazyWrappers() {
        RuntimeInstance runtime = runtime();
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long parentIdentity = host.mountElement("Parent", "parent");
            long firstIdentity = host.mountElement("Child", "first");
            long middleIdentity = host.mountElement("Child", "middle");
            long lastIdentity = host.mountElement("Child", "last");

            yes(host.commitElementRelations(
                    parentIdentity,
                    0L,
                    firstIdentity,
                    lastIdentity,
                    0L,
                    0L,
                    3), "parent relations");
            yes(host.commitElementRelations(
                    firstIdentity,
                    parentIdentity,
                    0L,
                    0L,
                    0L,
                    middleIdentity,
                    0), "first relations");
            yes(host.commitElementRelations(
                    middleIdentity,
                    parentIdentity,
                    0L,
                    0L,
                    firstIdentity,
                    lastIdentity,
                    0), "middle relations");
            yes(host.commitElementRelations(
                    lastIdentity,
                    parentIdentity,
                    0L,
                    0L,
                    middleIdentity,
                    0L,
                    0), "last relations");

            ReactNativeElement parent = context.createElement(parentIdentity);
            ReactNativeElement first = parent.getFirstElementChild();
            ReactNativeElement last = parent.getLastElementChild();
            ReactNativeElement middle = first.getNextElementSibling();
            same(first, context.createElement(firstIdentity), "first wrapper cached");
            same(last, context.createElement(lastIdentity), "last wrapper cached");
            same(middle, context.createElement(middleIdentity), "middle wrapper cached");
            same(parent, first.getParentElement(), "first parent");
            same(parent, middle.getParentElement(), "middle parent");
            same(first, middle.getPreviousElementSibling(), "middle previous");
            same(last, middle.getNextElementSibling(), "middle next");
            same(middle, last.getPreviousElementSibling(), "last previous");
            isNull(last.getNextElementSibling(), "last next absent");
            eq(3, parent.getChildElementCount(), "parent child count");
            eq(0, middle.getChildElementCount(), "leaf child count");
        } finally {
            runtime.leaveHostTurn();
            runtime.close();
            host.close();
        }
        passed++;
    }

    private void rootDefaultsAndGenerationSafety() {
        RuntimeInstance runtime = runtime();
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long parentIdentity = host.mountElement("Parent", "parent");
            long childIdentity = host.mountElement("Child", "old");
            yes(host.commitElementRelations(
                    parentIdentity,
                    0L,
                    childIdentity,
                    childIdentity,
                    0L,
                    0L,
                    1), "one child relation");
            yes(host.commitElementRelations(
                    childIdentity,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0), "hidden-root child relation");

            ReactNativeElement parent = context.createElement(parentIdentity);
            ReactNativeElement oldChild = parent.getFirstElementChild();
            isNull(oldChild.getParentElement(), "hidden root parent");
            eq(1, parent.getChildElementCount(), "initial count");

            yes(host.unmountElement(childIdentity), "old child unmounted");
            isNull(parent.getFirstElementChild(), "stale child relation unavailable");
            eq(0, parent.getChildElementCount(), "stale child count unavailable");
            no(oldChild.isConnected(), "old wrapper disconnected");

            long replacementIdentity = host.mountElement("Child", "replacement");
            isNull(parent.getFirstElementChild(), "slot reuse does not satisfy old identity");
            eq(0, parent.getChildElementCount(), "slot reuse does not restore count");
            yes(host.commitElementRelations(
                    parentIdentity,
                    0L,
                    replacementIdentity,
                    replacementIdentity,
                    0L,
                    0L,
                    1), "replacement relation committed");
            ReactNativeElement replacement = parent.getFirstElementChild();
            notSame(oldChild, replacement, "replacement wrapper differs");
            eq("replacement", replacement.getId(), "replacement metadata");

            yes(host.clearCommittedElementRelations(parentIdentity), "relations cleared");
            isNull(parent.getFirstElementChild(), "cleared first child");
            isNull(parent.getLastElementChild(), "cleared last child");
            eq(0, parent.getChildElementCount(), "cleared count");
            yes(parent.isConnected(), "clear keeps parent connected");
        } finally {
            runtime.leaveHostTurn();
            runtime.close();
            host.close();
        }
        passed++;
    }

    private void validationAndSnapshotIsolation() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long element = host.mountElement("View", "element");
        long first = host.mountElement("View", "first");
        long last = host.mountElement("View", "last");
        long sibling = host.mountElement("View", "sibling");

        no(host.commitElementRelations(element, element, 0, 0, 0, 0, 0), "self parent");
        no(host.commitElementRelations(element, Long.MAX_VALUE, 0, 0, 0, 0, 0),
                "stale parent");
        no(host.commitElementRelations(element, 0, 0, 0, 0, 0, -1), "negative count");
        no(host.commitElementRelations(element, 0, first, 0, 0, 0, 0),
                "zero count with child");
        no(host.commitElementRelations(element, 0, first, last, 0, 0, 1),
                "one count with distinct endpoints");
        no(host.commitElementRelations(element, 0, first, first, 0, 0, 2),
                "many count with one endpoint");
        no(host.commitElementRelations(element, 0, 0, 0, sibling, sibling, 0),
                "same previous and next");

        yes(host.commitElementRelations(element, 0, first, last, 0, sibling, 2),
                "valid relations");
        yes(host.commitLayout(element, 1, 2, 3, 4, 5, 6, 7, 8), "layout");
        yes(host.commitOffset(element, first, 9, 10), "offset");
        yes(host.commitClientAndScrollMetrics(element, 11, 12, 13, 14, 15, 16, 17, 18),
                "metrics");
        yes(host.clearCommittedElementRelations(element), "clear relations");

        RecordingRectSink rect = new RecordingRectSink();
        yes(host.measureBoundingClientRect(element, true, rect), "layout remains");
        eq(1, rect.writes, "layout write");
        RecordingOffsetSink offset = new RecordingOffsetSink();
        yes(host.measureOffset(element, offset), "offset remains");
        eq(first, offset.parentIdentity, "offset parent remains");
        raw(11.0, host.getClientWidth(element), "metrics remain");
        RecordingRelationSink relation = new RecordingRelationSink();
        no(host.readNextElementSibling(element, relation), "relations unavailable");
        eq(0, relation.writes, "unavailable relation writes none");

        no(host.clearCommittedElementRelations(Long.MAX_VALUE), "stale clear rejected");
        host.close();
        passed++;
    }

    private void growthOwnershipAndClose() throws Exception {
        final AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        final long parent = host.mountElement("Parent", "growth");
        long[] children = new long[128];
        for (int index = 0; index < children.length; index++) {
            children[index] = host.mountElement("Child", Integer.toString(index));
        }
        yes(host.commitElementRelations(
                parent,
                0L,
                children[0],
                children[children.length - 1],
                0L,
                0L,
                children.length), "grown relation table");
        RecordingRelationSink sink = new RecordingRelationSink();
        yes(host.readLastElementChild(parent, sink), "grown tail relation");
        eq(children[children.length - 1], sink.identity, "grown tail identity");
        eq(children.length, host.getChildElementCount(parent), "grown child count");

        final AtomicReference<Throwable> readFailure = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> writeFailure = new AtomicReference<Throwable>();
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                Looper.prepare();
                try {
                    host.readFirstElementChild(parent, new RecordingRelationSink());
                } catch (Throwable error) {
                    readFailure.set(error);
                }
                try {
                    host.commitElementRelations(parent, 0, 0, 0, 0, 0, 0);
                } catch (Throwable error) {
                    writeFailure.set(error);
                }
            }
        });
        thread.start();
        thread.join();
        instanceOf(IllegalStateException.class, readFailure.get(), "foreign relation read");
        instanceOf(IllegalStateException.class, writeFailure.get(), "foreign relation write");
        eq(children.length, host.getChildElementCount(parent), "foreign write made no change");

        host.close();
        sink.reset();
        no(host.readFirstElementChild(parent, sink), "closed relation unavailable");
        eq(0, sink.writes, "closed relation writes none");
        eq(0, host.getChildElementCount(parent), "closed count");
        isNull(host.getPublicInstance(parent), "closed public wrapper absent");
        try {
            host.commitElementRelations(parent, 0, 0, 0, 0, 0, 0);
            throw new AssertionError("closed relation mutation accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        passed++;
    }

    private static RuntimeInstance runtime() {
        return new RuntimeInstance(new ManualOwnerExecutor(), new CollectingErrorReporter());
    }

    private static final class RecordingRelationSink implements NativeElementRelationSink {
        long identity;
        int writes;

        @Override public void setRelatedElement(long value) {
            identity = value;
            writes++;
        }

        void reset() {
            identity = 0L;
            writes = 0;
        }
    }

    private static final class RecordingRectSink implements NativeElementRectSink {
        int writes;
        @Override public void setRect(double x, double y, double width, double height) {
            writes++;
        }
    }

    private static final class RecordingOffsetSink implements NativeElementOffsetSink {
        long parentIdentity;
        @Override public void setOffset(boolean hasParent, long identity, double top, double left) {
            if (!hasParent) throw new AssertionError("offset parent missing");
            parentIdentity = identity;
        }
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

    private static void isNull(Object value, String label) {
        if (value != null) throw new AssertionError(label + ": got " + value);
    }

    private static void instanceOf(Class<?> type, Throwable value, String label) {
        if (!type.isInstance(value)) throw new AssertionError(label + ": got " + value);
    }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void eq(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void raw(double expected, double actual, String label) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
