package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource;

/** Deterministic conformance for low-allocation runtime-owned host resources. */
public final class RuntimeOwnedResourceConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        new RuntimeOwnedResourceConformance().run();
    }

    private void run() throws Exception {
        testSlotsReuseWithoutRegistrationObjects();
        testForeignReleaseWinsRuntimeClose();
        testCloseContinuesAfterResourceFailure();
        System.out.println("runtime-owned resource conformance: " + passed + " tests passed");
    }

    private void testSlotsReuseWithoutRegistrationObjects() {
        Fixture fixture = new Fixture();
        RecordingResource released = new RecordingResource(fixture.runtime, null);
        RecordingResource retained = new RecordingResource(fixture.runtime, null);
        RecordingResource replacement = new RecordingResource(fixture.runtime, null);
        int releasedSlot;
        int retainedSlot;
        int replacementSlot;

        fixture.runtime.enterHostTurn();
        try {
            releasedSlot = fixture.runtime.registerOwnedResource(released);
            retainedSlot = fixture.runtime.registerOwnedResource(retained);
            assertTrue(
                    fixture.runtime.unregisterOwnedResource(released, releasedSlot),
                    "exact release succeeds");
            replacementSlot = fixture.runtime.registerOwnedResource(replacement);
        } finally {
            fixture.runtime.leaveHostTurn();
        }

        assertEquals(releasedSlot, replacementSlot, "released integer slot is reused");
        assertTrue(retainedSlot != replacementSlot, "live slot is not reused");
        fixture.runtime.close();
        assertEquals(0, released.closeCount, "released resource is not closed again");
        assertEquals(1, retained.closeCount, "retained resource closes once");
        assertEquals(1, replacement.closeCount, "replacement resource closes once");
        assertTrue(retained.closedInsideCleanupTurn, "close runs in owner cleanup turn");
        assertTrue(replacement.closedInsideCleanupTurn, "replacement close owner turn");
        pass();
    }

    private void testForeignReleaseWinsRuntimeClose() throws Exception {
        Fixture fixture = new Fixture();
        RecordingResource resource = new RecordingResource(fixture.runtime, null);
        final int[] slot = new int[1];
        fixture.runtime.enterHostTurn();
        try {
            slot[0] = fixture.runtime.registerOwnedResource(resource);
        } finally {
            fixture.runtime.leaveHostTurn();
        }

        final boolean[] released = new boolean[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                released[0] = fixture.runtime.unregisterOwnedResource(resource, slot[0]);
            }
        }, "runtime-resource-release");
        worker.start();
        worker.join();

        assertTrue(released[0], "foreign exact release succeeds");
        fixture.runtime.close();
        assertEquals(0, resource.closeCount, "foreign-released resource is not closed");
        pass();
    }

    private void testCloseContinuesAfterResourceFailure() {
        Fixture fixture = new Fixture();
        RecordingResource survivor = new RecordingResource(fixture.runtime, null);
        IllegalStateException expected = new IllegalStateException("resource-close-boom");
        RecordingResource failing = new RecordingResource(fixture.runtime, expected);

        fixture.runtime.enterHostTurn();
        try {
            fixture.runtime.registerOwnedResource(survivor);
            fixture.runtime.registerOwnedResource(failing);
        } finally {
            fixture.runtime.leaveHostTurn();
        }

        fixture.runtime.close();
        assertEquals(1, failing.closeCount, "failing resource close count");
        assertEquals(1, survivor.closeCount, "later resource still closes");
        assertEquals(1, fixture.errors.getEntries().size(), "resource close failure report count");
        assertSame(
                RuntimeErrorPhase.DISCARD,
                fixture.errors.getEntries().get(0).getPhase(),
                "resource close failure phase");
        assertSame(
                expected,
                fixture.errors.getEntries().get(0).getError(),
                "resource close failure identity");
        pass();
    }

    private void pass() {
        passed++;
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) throw new AssertionError(label);
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }

    private static final class RecordingResource implements RuntimeOwnedResource {
        final RuntimeInstance runtime;
        final RuntimeException failure;
        int closeCount;
        boolean closedInsideCleanupTurn;

        RecordingResource(RuntimeInstance runtime, RuntimeException failure) {
            this.runtime = runtime;
            this.failure = failure;
        }

        @Override
        public void closeForRuntime() {
            closeCount++;
            closedInsideCleanupTurn = runtime.isOwnerThread()
                    && runtime.isInsideHostTurn()
                    && !runtime.isClosed();
            if (failure != null) throw failure;
        }
    }
}
