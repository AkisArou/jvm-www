package io.github.akisarou.jvmwww.web.nativeelements.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;

/** Deterministic Java 8 conformance for element identity and inclusive containment. */
public final class NativeElementContainmentConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        new NativeElementContainmentConformance().run();
    }

    private void run() throws Exception {
        inclusiveContainmentAndIdentity();
        cycleSafety();
        ownership();
        System.out.println(
                "Native element containment conformance: " + passed + " tests passed");
    }

    private void inclusiveContainmentAndIdentity() {
        RuntimeInstance runtime = runtime();
        ChainHost host = new ChainHost();
        host.define(0L, false, 0L);
        host.define(11L, true, 0L);
        host.define(-7L, true, 11L);
        host.define(19L, true, 0L);
        host.define(23L, false, 0L);

        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            ReactNativeElement root = context.createElement(0L);
            ReactNativeElement child = context.createElement(11L);
            ReactNativeElement grandchild = context.createElement(-7L);
            ReactNativeElement peer = context.createElement(19L);
            ReactNativeElement detached = context.createElement(23L);

            yes(root.isSameNode(root), "same node self");
            yes(root.isSameNode(context.createElement(0L)), "same stable wrapper");
            no(root.isSameNode(child), "different node");
            no(root.isSameNode(null), "same node null");

            host.parentReads = 0;
            yes(root.contains(root), "self is inclusive descendant");
            eq(0, host.parentReads, "self fast path");

            yes(root.contains(child), "direct child");
            eq(1, host.parentReads, "direct child reads one parent");

            host.parentReads = 0;
            yes(root.contains(grandchild), "deep descendant");
            eq(2, host.parentReads, "deep descendant reads exact chain");
            yes(child.contains(grandchild), "nested ancestor");
            no(child.contains(root), "ancestor is not descendant");
            no(child.contains(peer), "sibling is not descendant");
            no(root.contains(detached), "detached element");
            no(root.contains(null), "contains null");

            ChainHost otherHost = new ChainHost();
            otherHost.define(0L, false, 0L);
            NativeElementContext otherContext = new NativeElementContext(runtime, otherHost);
            ReactNativeElement otherRoot = otherContext.createElement(0L);
            host.parentReads = 0;
            no(root.contains(otherRoot), "other renderer context");
            eq(0, host.parentReads, "cross-context fast path");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void cycleSafety() {
        RuntimeInstance runtime = runtime();
        ChainHost host = new ChainHost();
        host.define(101L, true, 102L);
        host.define(102L, true, 103L);
        host.define(103L, true, 102L);
        host.define(200L, false, 0L);

        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            ReactNativeElement a = context.createElement(101L);
            ReactNativeElement b = context.createElement(102L);
            ReactNativeElement c = context.createElement(103L);
            ReactNativeElement outside = context.createElement(200L);

            host.parentReads = 0;
            no(outside.contains(a), "cycle without ancestor terminates");
            yes(host.parentReads > 0 && host.parentReads <= 8, "bounded cycle reads");
            yes(b.contains(a), "cycle path still finds ancestor");
            yes(b.contains(c), "cycle reverse path finds ancestor");
            no(a.contains(c), "cycle without selected ancestor");
            yes(a.contains(a), "cycle node still contains itself");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void ownership() throws Exception {
        final RuntimeInstance runtime = runtime();
        final ChainHost host = new ChainHost();
        host.define(1L, false, 0L);
        final ReactNativeElement[] element = new ReactNativeElement[1];

        runtime.enterHostTurn();
        try {
            element[0] = new NativeElementContext(runtime, host).createElement(1L);
        } finally {
            runtime.leaveHostTurn();
        }

        rejects(new Action() {
            @Override
            public void run() {
                element[0].contains(element[0]);
            }
        }, "idle contains");
        rejects(new Action() {
            @Override
            public void run() {
                element[0].isSameNode(element[0]);
            }
        }, "idle same-node");

        int before = host.parentReads;
        final Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    element[0].contains(null);
                } catch (Throwable error) {
                    failure[0] = error;
                }
            }
        });
        thread.start();
        thread.join();
        yes(failure[0] instanceof IllegalStateException, "foreign contains");
        eq(before, host.parentReads, "foreign check precedes host");

        runtime.close();
        rejects(new Action() {
            @Override
            public void run() {
                element[0].contains(element[0]);
            }
        }, "closed contains");
        passed++;
    }

    private static RuntimeInstance runtime() {
        return new RuntimeInstance(new ManualOwnerExecutor(), new CollectingErrorReporter());
    }

    private static void closeTurn(RuntimeInstance runtime) {
        runtime.leaveHostTurn();
        runtime.close();
    }

    private static void rejects(Action action, String label) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(label + " accepted");
    }

    private interface Action {
        void run();
    }

    private static final class ChainHost implements NativeElementHost {
        private long[] identities = new long[8];
        private long[] parents = new long[8];
        private boolean[] hasParents = new boolean[8];
        private ReactNativeElement[] publicInstances = new ReactNativeElement[8];
        private int count;
        int parentReads;

        void define(long identity, boolean hasParent, long parentIdentity) {
            int index = find(identity);
            if (index < 0) {
                ensureCapacity(count + 1);
                index = count++;
                identities[index] = identity;
            }
            hasParents[index] = hasParent;
            parents[index] = parentIdentity;
        }

        @Override
        public ReactNativeElement getPublicInstance(long identity) {
            int index = find(identity);
            return index < 0 ? null : publicInstances[index];
        }

        @Override
        public ReactNativeElement registerPublicInstance(
                long identity, ReactNativeElement publicInstance) {
            int index = find(identity);
            if (index < 0) {
                throw new IllegalStateException("unknown test identity");
            }
            ReactNativeElement current = publicInstances[index];
            if (current != null && current != publicInstance) {
                throw new IllegalStateException("different public instance");
            }
            publicInstances[index] = publicInstance;
            return publicInstance;
        }

        @Override
        public boolean isConnected(long identity) {
            return find(identity) >= 0;
        }

        @Override
        public String getTagName(long identity) {
            return find(identity) < 0 ? null : "RN:View";
        }

        @Override
        public String getId(long identity) {
            return find(identity) < 0 ? null : "";
        }

        @Override
        public boolean measureBoundingClientRect(
                long identity, boolean includeTransform, NativeElementRectSink sink) {
            return false;
        }

        @Override
        public boolean measureOffset(long identity, NativeElementOffsetSink sink) {
            return false;
        }

        @Override
        public boolean readParentElement(long identity, NativeElementRelationSink sink) {
            parentReads++;
            int index = find(identity);
            if (index < 0 || !hasParents[index]) {
                return false;
            }
            sink.setRelatedElement(parents[index]);
            return true;
        }

        @Override
        public boolean readFirstElementChild(long identity, NativeElementRelationSink sink) {
            return false;
        }

        @Override
        public boolean readLastElementChild(long identity, NativeElementRelationSink sink) {
            return false;
        }

        @Override
        public boolean readPreviousElementSibling(
                long identity, NativeElementRelationSink sink) {
            return false;
        }

        @Override
        public boolean readNextElementSibling(long identity, NativeElementRelationSink sink) {
            return false;
        }

        @Override
        public int getChildElementCount(long identity) {
            return 0;
        }

        @Override public double getClientWidth(long identity) { return 0.0; }
        @Override public double getClientHeight(long identity) { return 0.0; }
        @Override public double getClientTop(long identity) { return 0.0; }
        @Override public double getClientLeft(long identity) { return 0.0; }
        @Override public double getScrollLeft(long identity) { return 0.0; }
        @Override public double getScrollTop(long identity) { return 0.0; }
        @Override public double getScrollWidth(long identity) { return 0.0; }
        @Override public double getScrollHeight(long identity) { return 0.0; }

        private int find(long identity) {
            for (int index = 0; index < count; index++) {
                if (identities[index] == identity) {
                    return index;
                }
            }
            return -1;
        }

        private void ensureCapacity(int required) {
            if (required <= identities.length) {
                return;
            }
            int newLength = identities.length << 1;
            long[] newIdentities = new long[newLength];
            long[] newParents = new long[newLength];
            boolean[] newHasParents = new boolean[newLength];
            ReactNativeElement[] newPublicInstances = new ReactNativeElement[newLength];
            System.arraycopy(identities, 0, newIdentities, 0, count);
            System.arraycopy(parents, 0, newParents, 0, count);
            System.arraycopy(hasParents, 0, newHasParents, 0, count);
            System.arraycopy(publicInstances, 0, newPublicInstances, 0, count);
            identities = newIdentities;
            parents = newParents;
            hasParents = newHasParents;
            publicInstances = newPublicInstances;
        }
    }

    private static void yes(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static void no(boolean value, String label) {
        yes(!value, label);
    }

    private static void eq(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(
                    label + ": expected " + expected + " got " + actual);
        }
    }
}
