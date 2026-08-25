package io.github.akisarou.jvmwww.web.nativeelements.android.testkit;

import android.os.Looper;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.nativeelements.HTMLCollection;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;
import io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost;

/** End-to-end Android conformance for live generation-safe element-child collections. */
public final class AndroidNativeElementCollectionConformance {
    private int passed;

    public static void main(String[] args) {
        Looper.prepare();
        new AndroidNativeElementCollectionConformance().run();
    }

    private void run() {
        sameObjectOrderAndNames();
        staleGenerationAndDetachedLifetime();
        System.out.println(
                "Android native element collection conformance: "
                        + passed
                        + " tests passed");
    }

    private void sameObjectOrderAndNames() {
        RuntimeInstance runtime = runtime();
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long parentIdentity = host.mountElement("View", "parent");
            long firstIdentity = host.mountElement("View", "alpha");
            long secondIdentity = host.mountElement("View", "duplicate");
            long thirdIdentity = host.mountElement("View", "duplicate");
            long nullIdentity = host.mountElement("View", "null");
            commitChildren(
                    host,
                    parentIdentity,
                    firstIdentity,
                    secondIdentity,
                    thirdIdentity,
                    nullIdentity);

            ReactNativeElement parent = context.createElement(parentIdentity);
            HTMLCollection children = parent.getChildren();
            same(children, parent.getChildren(), "children SameObject");
            eq(4, children.getLength(), "initial length");

            ReactNativeElement third = children.item(2.0);
            same(host.getPublicInstance(thirdIdentity), third, "indexed wrapper");
            isNull(host.getPublicInstance(firstIdentity), "first prefix remains unwrapped");
            isNull(host.getPublicInstance(secondIdentity), "second prefix remains unwrapped");

            ReactNativeElement duplicate = children.namedItem("duplicate");
            same(host.getPublicInstance(secondIdentity), duplicate, "first duplicate");
            ReactNativeElement nullNamed = children.namedItem(null);
            same(host.getPublicInstance(nullIdentity), nullNamed, "null DOMString conversion");
            isNull(children.namedItem(""), "empty supported name");

            commitChildren(host, parentIdentity, thirdIdentity, firstIdentity);
            same(children, parent.getChildren(), "same collection after reorder");
            eq(2, children.getLength(), "reordered length");
            same(third, children.item(0.0), "reordered first");
            same(third, children.namedItem("duplicate"), "reordered duplicate");
            ReactNativeElement first = children.item(1.0);
            same(host.getPublicInstance(firstIdentity), first, "reordered second");
        } finally {
            runtime.leaveHostTurn();
            runtime.close();
            host.close();
        }
        passed++;
    }

    private void staleGenerationAndDetachedLifetime() {
        RuntimeInstance runtime = runtime();
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long parentIdentity = host.mountElement("View", "parent");
            long firstIdentity = host.mountElement("View", "first");
            long secondIdentity = host.mountElement("View", "second");
            commitChildren(host, parentIdentity, firstIdentity, secondIdentity);

            ReactNativeElement parent = context.createElement(parentIdentity);
            ReactNativeElement first = context.createElement(firstIdentity);
            HTMLCollection children = parent.getChildren();
            eq(2, children.getLength(), "starting length");

            yes(host.unmountElement(firstIdentity), "first unmounted");
            eq(0, children.getLength(), "stale first endpoint invalidates snapshot");
            isNull(children.item(0.0), "stale first item absent");

            long replacementIdentity = host.mountElement("View", "replacement");
            yes(replacementIdentity != firstIdentity, "reused slot has new generation");
            eq(0, children.getLength(), "slot reuse does not alias old relation");
            commitChildren(host, parentIdentity, replacementIdentity, secondIdentity);
            eq(2, children.getLength(), "recommitted replacement visible");
            ReactNativeElement replacement = children.item(0.0);
            eq("replacement", replacement.getId(), "replacement wrapper");
            no(first.isConnected(), "old wrapper stays disconnected");

            yes(host.unmountElement(parentIdentity), "parent unmounted");
            same(children, parent.getChildren(), "detached collection SameObject");
            eq(0, children.getLength(), "detached collection empty");
            isNull(children.namedItem("replacement"), "detached named lookup empty");

            long newParentIdentity = host.mountElement("View", "new-parent");
            ReactNativeElement newParent = context.createElement(newParentIdentity);
            HTMLCollection newChildren = newParent.getChildren();
            notSame(children, newChildren, "new generation gets distinct collection");
            eq(0, newChildren.getLength(), "new parent starts empty");

            host.close();
            same(children, parent.getChildren(), "collection retained after host close");
            eq(0, children.getLength(), "closed host old collection empty");
            eq(0, newChildren.getLength(), "closed host new collection empty");
        } finally {
            runtime.leaveHostTurn();
            runtime.close();
            if (!host.isClosed()) {
                host.close();
            }
        }
        passed++;
    }

    private static void commitChildren(
            AndroidNativeElementHost host,
            long parentIdentity,
            long... childIdentities) {
        long firstIdentity = childIdentities.length == 0 ? 0L : childIdentities[0];
        long lastIdentity = childIdentities.length == 0
                ? 0L
                : childIdentities[childIdentities.length - 1];
        yes(host.commitElementRelations(
                parentIdentity,
                0L,
                firstIdentity,
                lastIdentity,
                0L,
                0L,
                childIdentities.length),
                "parent relations committed");
        for (int index = 0; index < childIdentities.length; index++) {
            long previousIdentity = index == 0 ? 0L : childIdentities[index - 1];
            long nextIdentity = index + 1 == childIdentities.length
                    ? 0L
                    : childIdentities[index + 1];
            yes(host.commitElementRelations(
                    childIdentities[index],
                    parentIdentity,
                    0L,
                    0L,
                    previousIdentity,
                    nextIdentity,
                    0),
                    "child relations committed " + index);
        }
    }

    private static RuntimeInstance runtime() {
        return new RuntimeInstance(new ManualOwnerExecutor(), new CollectingErrorReporter());
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

    private static void eq(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
