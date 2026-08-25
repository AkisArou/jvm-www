package io.github.akisarou.jvmwww.web.nativeelements.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext;
import io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement;

/** Deterministic Java 8 conformance for element-only parent, child, and sibling relations. */
public final class NativeElementRelationConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        new NativeElementRelationConformance().run();
    }

    private void run() throws Exception {
        stableRelationsAndLazyWrappers();
        unavailableAndZeroIdentity();
        sinkContractAndReentry();
        ownership();
        System.out.println("Native element relation conformance: " + passed + " tests passed");
    }

    private void stableRelationsAndLazyWrappers() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            long elementIdentity = Long.MIN_VALUE + 101L;
            long parentIdentity = Long.MIN_VALUE + 102L;
            ReactNativeElement parent = context.createElement(parentIdentity);
            ReactNativeElement element = context.createElement(elementIdentity);

            host.parentElementAvailable = true;
            host.parentElementIdentity = parentIdentity;
            host.firstElementChildAvailable = true;
            host.firstElementChildIdentity = 301L;
            host.lastElementChildAvailable = true;
            host.lastElementChildIdentity = 302L;
            host.previousElementSiblingAvailable = true;
            host.previousElementSiblingIdentity = 401L;
            host.nextElementSiblingAvailable = true;
            host.nextElementSiblingIdentity = 402L;
            host.childElementCount = 2;

            same(parent, element.getParentElement(), "published parent wrapper");
            ReactNativeElement first = element.getFirstElementChild();
            ReactNativeElement last = element.getLastElementChild();
            ReactNativeElement previous = element.getPreviousElementSibling();
            ReactNativeElement next = element.getNextElementSibling();
            notSame(first, last, "distinct child wrappers");
            notSame(previous, next, "distinct sibling wrappers");
            same(first, context.createElement(301L), "first child registered once");
            same(last, context.createElement(302L), "last child registered once");
            same(previous, element.getPreviousElementSibling(), "previous sibling stable");
            same(next, element.getNextElementSibling(), "next sibling stable");
            eq(2, element.getChildElementCount(), "child element count");
            eq(elementIdentity, host.identity, "relation identity forwarded");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void unavailableAndZeroIdentity() {
        RuntimeInstance runtime = runtime();
        FakeNativeElementHost host = new FakeNativeElementHost();
        runtime.enterHostTurn();
        try {
            NativeElementContext context = new NativeElementContext(runtime, host);
            ReactNativeElement element = context.createElement(77L);
            isNull(element.getParentElement(), "unavailable parent");
            isNull(element.getFirstElementChild(), "unavailable first child");
            isNull(element.getLastElementChild(), "unavailable last child");
            isNull(element.getPreviousElementSibling(), "unavailable previous sibling");
            isNull(element.getNextElementSibling(), "unavailable next sibling");
            eq(0, element.getChildElementCount(), "unavailable child count");

            host.firstElementChildAvailable = true;
            host.firstElementChildIdentity = 0L;
            ReactNativeElement zeroIdentity = element.getFirstElementChild();
            same(zeroIdentity, context.createElement(0L), "core does not reserve identity zero");

            host.childElementCount = -1;
            try {
                element.getChildElementCount();
                throw new AssertionError("negative child count accepted");
            } catch (IllegalStateException expected) {
                // Host contract violations fail explicitly.
            }
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
            host.parentElementIdentity = 20L;

            host.relationMode = FakeNativeElementHost.NO_WRITE;
            rejectsParent(element, "true without write");
            host.relationMode = FakeNativeElementHost.FALSE_WRITE;
            rejectsParent(element, "false after write");
            host.relationMode = FakeNativeElementHost.TWICE;
            rejectsParent(element, "double write");
            host.relationMode = FakeNativeElementHost.THROW;
            try {
                element.getParentElement();
                throw new AssertionError("host exception missing");
            } catch (FakeNativeElementHost.Marker expected) {
                // Exact host exception escapes.
            }

            host.relationMode = FakeNativeElementHost.NORMAL;
            host.parentElementAvailable = true;
            host.parentElementIdentity = 10L;
            rejectsParent(element, "self relation");

            host.parentElementIdentity = 20L;
            host.retainRelation = true;
            ReactNativeElement parent = element.getParentElement();
            same(parent, element.getParentElement(), "relation recovery");
            try {
                host.retainedRelation.setRelatedElement(30L);
                throw new AssertionError("retained relation sink accepted");
            } catch (IllegalStateException expected) {
                // Sink lifetime is one synchronous host call.
            }

            host.reentrant = element;
            host.relationMode = FakeNativeElementHost.REENTER;
            host.nextElementSiblingAvailable = true;
            host.nextElementSiblingIdentity = 40L;
            rejectsNext(element, "relation-to-rectangle reentry");
            host.relationMode = FakeNativeElementHost.NORMAL;
            host.reentrant = null;
            same(parent, element.getParentElement(), "reentry recovery");
        } finally {
            closeTurn(runtime);
        }
        passed++;
    }

    private void ownership() throws Exception {
        final RuntimeInstance runtime = runtime();
        final FakeNativeElementHost host = new FakeNativeElementHost();
        final ReactNativeElement[] element = new ReactNativeElement[1];
        runtime.enterHostTurn();
        try {
            element[0] = new NativeElementContext(runtime, host).createElement(91L);
        } finally {
            runtime.leaveHostTurn();
        }

        int before = host.calls;
        try {
            element[0].getParentElement();
            throw new AssertionError("idle relation access accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        eq(before, host.calls, "idle check before host");

        final Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    element[0].getNextElementSibling();
                } catch (Throwable error) {
                    failure[0] = error;
                }
            }
        });
        thread.start();
        thread.join();
        yes(failure[0] instanceof IllegalStateException, "foreign relation access");
        eq(before, host.calls, "foreign check before host");

        runtime.close();
        try {
            element[0].getChildElementCount();
            throw new AssertionError("closed child count accepted");
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

    private static void rejectsParent(ReactNativeElement element, String label) {
        try {
            element.getParentElement();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(label + " accepted");
    }

    private static void rejectsNext(ReactNativeElement element, String label) {
        try {
            element.getNextElementSibling();
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

    private static void notSame(Object expected, Object actual, String label) {
        if (expected == actual) throw new AssertionError(label);
    }

    private static void isNull(Object value, String label) {
        if (value != null) throw new AssertionError(label + ": got " + value);
    }

    private static void eq(long expected, long actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + actual);
    }
}
