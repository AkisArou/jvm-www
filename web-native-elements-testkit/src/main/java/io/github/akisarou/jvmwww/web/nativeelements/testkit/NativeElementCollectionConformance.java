package io.github.akisarou.jvmwww.web.nativeelements.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.nativeelements.HTMLCollection;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;

/** Deterministic Java 8 conformance for live same-object element-child collections. */
public final class NativeElementCollectionConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        new NativeElementCollectionConformance().run();
    }

    private void run() throws Exception {
        sameObjectAndIndexedReads();
        namedLookupAndLiveUpdates();
        detachedAndOwnership();
        System.out.println(
                "Native element collection conformance: " + passed + " tests passed");
    }

    private void sameObjectAndIndexedReads() {
        RuntimeInstance runtime = runtime();
        GraphHost host = new GraphHost();
        long parentIdentity = 0L;
        long firstIdentity = 11L;
        long secondIdentity = -12L;
        long thirdIdentity = Long.MIN_VALUE + 13L;
        host.add(parentIdentity, "parent");
        host.add(firstIdentity, "first");
        host.add(secondIdentity, "second");
        host.add(thirdIdentity, "third");
        host.setChildren(parentIdentity, firstIdentity, secondIdentity, thirdIdentity);

        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            ReactNativeElement parent = context.createElement(parentIdentity);
            HTMLCollection children = parent.getChildren();
            same(children, parent.getChildren(), "children SameObject");
            eq(3, children.getLength(), "initial length");

            int registrations = host.registrationCount;
            ReactNativeElement third = children.item(2.0);
            eq("third", third.getId(), "indexed child");
            eq(registrations + 1, host.registrationCount, "only requested wrapper exposed");
            isNull(host.peekPublicInstance(firstIdentity), "first intermediate not wrapped");
            isNull(host.peekPublicInstance(secondIdentity), "second intermediate not wrapped");

            ReactNativeElement second = children.item(1.9);
            eq("second", second.getId(), "fraction truncates");
            same(second, children.item(-4294967295.0), "negative modulo conversion");
            eq("first", children.item(Double.NaN).getId(), "NaN converts to zero");
            eq("first", children.item(Double.POSITIVE_INFINITY).getId(),
                    "infinity converts to zero");
            eq("first", children.item(4294967296.0).getId(), "2^32 wraps to zero");
            isNull(children.item(-1.0), "minus one wraps out of range");
            isNull(children.item(3.0), "length boundary");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void namedLookupAndLiveUpdates() {
        RuntimeInstance runtime = runtime();
        GraphHost host = new GraphHost();
        long parentIdentity = 101L;
        long firstIdentity = 102L;
        long secondIdentity = 103L;
        long thirdIdentity = 104L;
        long nullIdentity = 105L;
        host.add(parentIdentity, "parent");
        host.add(firstIdentity, "alpha");
        host.add(secondIdentity, "duplicate");
        host.add(thirdIdentity, "duplicate");
        host.add(nullIdentity, "null");
        host.setChildren(
                parentIdentity,
                firstIdentity,
                secondIdentity,
                thirdIdentity,
                nullIdentity);

        runtime.enterHostTurn();
        try {
            ReactNativeElement parent =
                    new NativeElementContext(runtime, host).createElement(parentIdentity);
            HTMLCollection children = parent.getChildren();
            int registrations = host.registrationCount;
            ReactNativeElement duplicate = children.namedItem("duplicate");
            eq("duplicate", duplicate.getId(), "named duplicate");
            same(host.peekPublicInstance(secondIdentity), duplicate, "first duplicate in tree order");
            eq(registrations + 1, host.registrationCount, "named lookup wraps only match");
            isNull(host.peekPublicInstance(firstIdentity), "named scan does not wrap prefix");
            ReactNativeElement nullNamed = children.namedItem(null);
            same(host.peekPublicInstance(nullIdentity), nullNamed, "null DOMString conversion");
            isNull(children.namedItem(""), "empty supported name");
            isNull(children.namedItem("missing"), "missing name");

            host.setChildren(parentIdentity, thirdIdentity, firstIdentity);
            same(children, parent.getChildren(), "collection identity across update");
            eq(2, children.getLength(), "live updated length");
            ReactNativeElement reorderedFirst = children.item(0.0);
            same(host.peekPublicInstance(thirdIdentity), reorderedFirst, "live reordered first");
            ReactNativeElement liveDuplicate = children.namedItem("duplicate");
            same(host.peekPublicInstance(thirdIdentity), liveDuplicate, "live duplicate order");

            host.setChildren(parentIdentity);
            eq(0, children.getLength(), "live emptied length");
            isNull(children.item(0.0), "live emptied item");
            isNull(children.namedItem("alpha"), "live emptied named item");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void detachedAndOwnership() throws Exception {
        final RuntimeInstance runtime = runtime();
        final GraphHost host = new GraphHost();
        long parentIdentity = 201L;
        long childIdentity = 202L;
        host.add(parentIdentity, "parent");
        host.add(childIdentity, "child");
        host.setChildren(parentIdentity, childIdentity);
        final HTMLCollection[] collection = new HTMLCollection[1];
        final ReactNativeElement[] parent = new ReactNativeElement[1];

        runtime.enterHostTurn();
        try {
            parent[0] = new NativeElementContext(runtime, host).createElement(parentIdentity);
            collection[0] = parent[0].getChildren();
            host.disconnect(parentIdentity);
            same(collection[0], parent[0].getChildren(), "detached SameObject");
            eq(0, collection[0].getLength(), "detached length");
            isNull(collection[0].item(0.0), "detached item");
        } finally {
            runtime.leaveHostTurn();
        }

        int calls = host.calls;
        final Throwable[] failure = new Throwable[1];
        Thread foreign = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    collection[0].getLength();
                } catch (Throwable error) {
                    failure[0] = error;
                }
            }
        });
        foreign.start();
        foreign.join();
        yes(failure[0] instanceof IllegalStateException, "foreign collection access");
        eq(calls, host.calls, "foreign rejection precedes host");

        runtime.close();
        calls = host.calls;
        try {
            collection[0].namedItem("child");
            throw new AssertionError("closed collection access accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        eq(calls, host.calls, "closed rejection precedes host");
        passed++;
    }

    private static RuntimeInstance runtime() {
        return new RuntimeInstance(new ManualOwnerExecutor(), new CollectingErrorReporter());
    }

    private static void closeTurn(RuntimeInstance runtime) {
        runtime.leaveHostTurn();
        runtime.close();
    }

    private static final class GraphHost implements NativeElementHost {
        private static final int CAPACITY = 16;

        private final long[] identities = new long[CAPACITY];
        private final boolean[] connected = new boolean[CAPACITY];
        private final String[] ids = new String[CAPACITY];
        private final boolean[] hasParent = new boolean[CAPACITY];
        private final long[] parents = new long[CAPACITY];
        private final boolean[] hasFirst = new boolean[CAPACITY];
        private final long[] first = new long[CAPACITY];
        private final boolean[] hasLast = new boolean[CAPACITY];
        private final long[] last = new long[CAPACITY];
        private final boolean[] hasPrevious = new boolean[CAPACITY];
        private final long[] previous = new long[CAPACITY];
        private final boolean[] hasNext = new boolean[CAPACITY];
        private final long[] next = new long[CAPACITY];
        private final int[] counts = new int[CAPACITY];
        private final ReactNativeElement[] publicInstances = new ReactNativeElement[CAPACITY];
        private int size;
        int calls;
        int registrationCount;

        void add(long identity, String id) {
            int index = size++;
            identities[index] = identity;
            connected[index] = true;
            ids[index] = id;
        }

        void disconnect(long identity) {
            int index = indexOf(identity);
            if (index >= 0) {
                connected[index] = false;
                publicInstances[index] = null;
            }
        }

        void setChildren(long parentIdentity, long... childIdentities) {
            int parentIndex = requiredIndex(parentIdentity);
            hasFirst[parentIndex] = childIdentities.length != 0;
            hasLast[parentIndex] = childIdentities.length != 0;
            counts[parentIndex] = childIdentities.length;
            if (childIdentities.length != 0) {
                first[parentIndex] = childIdentities[0];
                last[parentIndex] = childIdentities[childIdentities.length - 1];
            }
            for (int index = 0; index < size; index++) {
                if (hasParent[index] && parents[index] == parentIdentity) {
                    hasParent[index] = false;
                    hasPrevious[index] = false;
                    hasNext[index] = false;
                }
            }
            for (int position = 0; position < childIdentities.length; position++) {
                int childIndex = requiredIndex(childIdentities[position]);
                hasParent[childIndex] = true;
                parents[childIndex] = parentIdentity;
                hasPrevious[childIndex] = position != 0;
                hasNext[childIndex] = position + 1 != childIdentities.length;
                if (position != 0) {
                    previous[childIndex] = childIdentities[position - 1];
                }
                if (position + 1 != childIdentities.length) {
                    next[childIndex] = childIdentities[position + 1];
                }
            }
        }

        ReactNativeElement peekPublicInstance(long identity) {
            int index = indexOf(identity);
            return index < 0 ? null : publicInstances[index];
        }

        @Override
        public ReactNativeElement getPublicInstance(long identity) {
            calls++;
            int index = activeIndex(identity);
            return index < 0 ? null : publicInstances[index];
        }

        @Override
        public ReactNativeElement registerPublicInstance(
                long identity, ReactNativeElement publicInstance) {
            calls++;
            int index = activeIndex(identity);
            if (index < 0) {
                throw new IllegalStateException("stale identity");
            }
            ReactNativeElement current = publicInstances[index];
            if (current != null && current != publicInstance) {
                throw new IllegalStateException("different wrapper");
            }
            if (current == null) {
                registrationCount++;
                publicInstances[index] = publicInstance;
            }
            return publicInstance;
        }

        @Override
        public boolean isConnected(long identity) {
            calls++;
            return activeIndex(identity) >= 0;
        }

        @Override
        public String getTagName(long identity) {
            calls++;
            return activeIndex(identity) < 0 ? null : "RN:View";
        }

        @Override
        public String getId(long identity) {
            calls++;
            int index = activeIndex(identity);
            return index < 0 ? null : ids[index];
        }

        @Override
        public boolean readParentElement(long identity, NativeElementRelationSink sink) {
            return relation(identity, sink, hasParent, parents);
        }

        @Override
        public boolean readFirstElementChild(long identity, NativeElementRelationSink sink) {
            return relation(identity, sink, hasFirst, first);
        }

        @Override
        public boolean readLastElementChild(long identity, NativeElementRelationSink sink) {
            return relation(identity, sink, hasLast, last);
        }

        @Override
        public boolean readPreviousElementSibling(long identity, NativeElementRelationSink sink) {
            return relation(identity, sink, hasPrevious, previous);
        }

        @Override
        public boolean readNextElementSibling(long identity, NativeElementRelationSink sink) {
            return relation(identity, sink, hasNext, next);
        }

        @Override
        public int getChildElementCount(long identity) {
            calls++;
            int index = activeIndex(identity);
            if (index < 0 || counts[index] == 0) {
                return 0;
            }
            return activeIndex(first[index]) < 0 || activeIndex(last[index]) < 0
                    ? 0
                    : counts[index];
        }

        private boolean relation(
                long identity,
                NativeElementRelationSink sink,
                boolean[] available,
                long[] values) {
            calls++;
            int index = activeIndex(identity);
            if (index < 0 || !available[index] || activeIndex(values[index]) < 0) {
                return false;
            }
            sink.setRelatedElement(values[index]);
            return true;
        }

        private int requiredIndex(long identity) {
            int index = indexOf(identity);
            if (index < 0) throw new AssertionError("unknown identity " + identity);
            return index;
        }

        private int activeIndex(long identity) {
            int index = indexOf(identity);
            return index >= 0 && connected[index] ? index : -1;
        }

        private int indexOf(long identity) {
            for (int index = 0; index < size; index++) {
                if (identities[index] == identity) return index;
            }
            return -1;
        }

        @Override public boolean measureBoundingClientRect(
                long identity, boolean transformed, NativeElementRectSink sink) { calls++; return false; }
        @Override public boolean measureOffset(long identity, NativeElementOffsetSink sink) { calls++; return false; }
        @Override public double getClientWidth(long identity) { calls++; return 0.0; }
        @Override public double getClientHeight(long identity) { calls++; return 0.0; }
        @Override public double getClientTop(long identity) { calls++; return 0.0; }
        @Override public double getClientLeft(long identity) { calls++; return 0.0; }
        @Override public double getScrollLeft(long identity) { calls++; return 0.0; }
        @Override public double getScrollTop(long identity) { calls++; return 0.0; }
        @Override public double getScrollWidth(long identity) { calls++; return 0.0; }
        @Override public double getScrollHeight(long identity) { calls++; return 0.0; }
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
