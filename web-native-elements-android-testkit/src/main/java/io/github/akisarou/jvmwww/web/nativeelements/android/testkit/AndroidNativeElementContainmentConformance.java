package io.github.akisarou.jvmwww.web.nativeelements.android.testkit;

import android.os.Looper;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;
import io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost;

/** End-to-end containment over the Android exact-generation relation table. */
public final class AndroidNativeElementContainmentConformance {
    private int passed;

    public static void main(String[] args) {
        Looper.prepare();
        new AndroidNativeElementContainmentConformance().run();
    }

    private void run() {
        hierarchyAndGenerationReuse();
        cycleSafety();
        System.out.println(
                "Android native element containment conformance: "
                        + passed
                        + " tests passed");
    }

    private void hierarchyAndGenerationReuse() {
        RuntimeInstance runtime = runtime();
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();

        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long rootIdentity = host.mountElement("Root", "root");
            long childIdentity = host.mountElement("View", "child");
            long grandchildIdentity = host.mountElement("Text", "grandchild");
            long peerIdentity = host.mountElement("View", "peer");
            long detachedIdentity = host.mountElement("View", "detached");

            yes(host.commitElementRelations(
                    rootIdentity,
                    0L,
                    childIdentity,
                    peerIdentity,
                    0L,
                    0L,
                    2), "root relations");
            yes(host.commitElementRelations(
                    childIdentity,
                    rootIdentity,
                    grandchildIdentity,
                    grandchildIdentity,
                    0L,
                    peerIdentity,
                    1), "child relations");
            yes(host.commitElementRelations(
                    grandchildIdentity,
                    childIdentity,
                    0L,
                    0L,
                    0L,
                    0L,
                    0), "grandchild relations");
            yes(host.commitElementRelations(
                    peerIdentity,
                    rootIdentity,
                    0L,
                    0L,
                    childIdentity,
                    0L,
                    0), "peer relations");
            yes(host.commitElementRelations(
                    detachedIdentity,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0), "detached relations");

            ReactNativeElement root = context.createElement(rootIdentity);
            ReactNativeElement child = context.createElement(childIdentity);
            ReactNativeElement grandchild = context.createElement(grandchildIdentity);
            ReactNativeElement peer = context.createElement(peerIdentity);
            ReactNativeElement detached = context.createElement(detachedIdentity);

            yes(root.contains(root), "root contains self");
            yes(root.contains(child), "root contains child");
            yes(root.contains(grandchild), "root contains grandchild");
            yes(root.contains(peer), "root contains peer");
            yes(child.contains(grandchild), "child contains grandchild");
            no(child.contains(peer), "child excludes peer");
            no(root.contains(detached), "root excludes detached");

            yes(host.unmountElement(childIdentity), "child unmounted");
            yes(child.contains(child), "detached child contains itself");
            no(root.contains(child), "root excludes stale child");
            no(root.contains(grandchild), "stale parent breaks ancestry");

            long replacementIdentity = host.mountElement("View", "replacement");
            yes(replacementIdentity != childIdentity, "replacement generation differs");
            ReactNativeElement replacement = context.createElement(replacementIdentity);
            no(root.contains(replacement), "replacement initially detached");
            no(root.contains(grandchild), "slot reuse does not repair old relation");

            yes(host.commitElementRelations(
                    replacementIdentity,
                    rootIdentity,
                    grandchildIdentity,
                    grandchildIdentity,
                    0L,
                    peerIdentity,
                    1), "replacement relations");
            yes(host.commitElementRelations(
                    grandchildIdentity,
                    replacementIdentity,
                    0L,
                    0L,
                    0L,
                    0L,
                    0), "grandchild recommitted");
            yes(host.commitElementRelations(
                    rootIdentity,
                    0L,
                    replacementIdentity,
                    peerIdentity,
                    0L,
                    0L,
                    2), "root recommitted");

            yes(root.contains(replacement), "root contains replacement");
            yes(root.contains(grandchild), "root contains recommitted grandchild");
            yes(replacement.contains(grandchild), "replacement contains grandchild");
            no(child.isSameNode(replacement), "old and replacement wrappers differ");

            host.close();
            yes(root.contains(root), "closed host keeps inclusive self");
            no(root.contains(grandchild), "closed host exposes no ancestry");
        } finally {
            runtime.leaveHostTurn();
        }
        runtime.close();
        passed++;
    }

    private void cycleSafety() {
        RuntimeInstance runtime = runtime();
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();

        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long aIdentity = host.mountElement("View", "a");
            long bIdentity = host.mountElement("View", "b");
            long outsideIdentity = host.mountElement("View", "outside");

            yes(host.commitElementRelations(
                    aIdentity, bIdentity, 0L, 0L, 0L, 0L, 0), "a cycle edge");
            yes(host.commitElementRelations(
                    bIdentity, aIdentity, 0L, 0L, 0L, 0L, 0), "b cycle edge");
            yes(host.commitElementRelations(
                    outsideIdentity, 0L, 0L, 0L, 0L, 0L, 0), "outside root");

            ReactNativeElement a = context.createElement(aIdentity);
            ReactNativeElement b = context.createElement(bIdentity);
            ReactNativeElement outside = context.createElement(outsideIdentity);

            no(outside.contains(a), "two-node cycle terminates");
            yes(a.contains(b), "cycle still reports reached ancestor");
            yes(b.contains(a), "cycle reverse ancestor");
            yes(a.isSameNode(a), "same-node remains reference identity");
        } finally {
            runtime.leaveHostTurn();
        }
        runtime.close();
        host.close();
        passed++;
    }

    private static RuntimeInstance runtime() {
        return new RuntimeInstance(new ManualOwnerExecutor(), new CollectingErrorReporter());
    }

    private static void yes(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static void no(boolean value, String label) {
        yes(!value, label);
    }
}
