package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free conformance tests for the first scheduler slice. */
public final class RuntimeInstanceConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        RuntimeInstanceConformance suite = new RuntimeInstanceConformance();
        suite.run();
    }

    private void run() throws Exception {
        testOutermostTurnOwnsCheckpoint();
        testMicrotasksDrainFifoToExhaustion();
        testActiveTurnQueuesNoWake();
        testIdleMicrotaskQueuesOneWake();
        testForeignBurstCoalescesWakeAndCheckpointsBetweenTasks();
        testForeignAdmissionWhileWakeRunsIsNotLost();
        testConcurrentForeignAdmissionStress();
        testForeignThreadCannotExecuteRuntimeDirectly();
        testHostTaskBudgetRepostsWithoutSplittingCheckpoint();
        testHostTaskFailureReportsAndStillCheckpoints();
        testMicrotaskFailureReportsAndDrainContinues();
        testFatalErrorEscapesAndRestoresSchedulerState();
        testCloseDiscardsQueuedTasks();
        testCloseDiscardsIdleMicrotasks();
        testRuntimeInstancesAreIsolated();

        System.out.println("runtime-core conformance: " + passed + " tests passed");
    }

    private void testOutermostTurnOwnsCheckpoint() {
        Fixture fixture = new Fixture();
        final List<String> trace = new ArrayList<String>();

        fixture.runtime.enterHostTurn();
        try {
            trace.add("outer-start");
            fixture.runtime.queueMicrotask(append(trace, "microtask"));

            fixture.runtime.enterHostTurn();
            try {
                trace.add("nested");
            } finally {
                fixture.runtime.leaveHostTurn();
            }

            trace.add("outer-end");
            assertListEquals(
                    Arrays.asList("outer-start", "nested", "outer-end"),
                    trace,
                    "nested leave must not create a checkpoint");
        } finally {
            fixture.runtime.leaveHostTurn();
        }

        assertListEquals(
                Arrays.asList("outer-start", "nested", "outer-end", "microtask"),
                trace,
                "outermost leave must drain microtasks");
        pass();
    }

    private void testMicrotasksDrainFifoToExhaustion() {
        Fixture fixture = new Fixture();
        final List<String> trace = new ArrayList<String>();

        fixture.runtime.enterHostTurn();
        try {
            fixture.runtime.queueMicrotask(new RuntimeTask() {
                @Override
                public void execute(RuntimeInstance runtime) {
                    trace.add("a");
                    runtime.queueMicrotask(append(trace, "c"));
                }
            });
            fixture.runtime.queueMicrotask(append(trace, "b"));
        } finally {
            fixture.runtime.leaveHostTurn();
        }

        assertListEquals(Arrays.asList("a", "b", "c"), trace, "microtask FIFO ordering");
        pass();
    }

    private void testActiveTurnQueuesNoWake() {
        Fixture fixture = new Fixture();

        fixture.runtime.enterHostTurn();
        try {
            fixture.runtime.queueMicrotask(noOp());
            assertEquals(0, fixture.executor.getPostCount(), "active-turn microtask owner posts");
        } finally {
            fixture.runtime.leaveHostTurn();
        }

        assertEquals(0, fixture.executor.getPostCount(), "checkpoint must not leave a stale wake");
        pass();
    }

    private void testIdleMicrotaskQueuesOneWake() {
        Fixture fixture = new Fixture();
        final List<String> trace = new ArrayList<String>();

        fixture.runtime.queueMicrotask(append(trace, "a"));
        fixture.runtime.queueMicrotask(append(trace, "b"));

        assertEquals(1, fixture.executor.getPostCount(), "idle microtask burst owner posts");
        assertEquals(1, fixture.executor.getPendingCallbackCount(), "idle microtask callbacks");

        fixture.executor.runAll();
        assertListEquals(Arrays.asList("a", "b"), trace, "idle microtask checkpoint");
        pass();
    }

    private void testForeignBurstCoalescesWakeAndCheckpointsBetweenTasks() throws Exception {
        final Fixture fixture = new Fixture();
        final List<String> trace = new ArrayList<String>();
        final CountDownLatch admitted = new CountDownLatch(1);

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                fixture.runtime.admitHostTask(new RuntimeTask() {
                    @Override
                    public void execute(RuntimeInstance runtime) {
                        trace.add("host-1");
                        runtime.queueMicrotask(append(trace, "micro-1"));
                    }
                });
                fixture.runtime.admitHostTask(new RuntimeTask() {
                    @Override
                    public void execute(RuntimeInstance runtime) {
                        trace.add("host-2");
                        runtime.queueMicrotask(append(trace, "micro-2"));
                    }
                });
                admitted.countDown();
            }
        }, "foreign-admitter");
        worker.start();
        admitted.await();
        worker.join();

        assertEquals(1, fixture.executor.getPostCount(), "foreign burst owner posts");
        fixture.executor.runAll();

        assertListEquals(
                Arrays.asList("host-1", "micro-1", "host-2", "micro-2"),
                trace,
                "each admitted host task needs its own checkpoint");
        pass();
    }

    private void testForeignAdmissionWhileWakeRunsIsNotLost() {
        final Fixture fixture = new Fixture(1);
        final List<String> trace = new ArrayList<String>();

        fixture.runtime.admitHostTask(new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) throws InterruptedException {
                trace.add("first");
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        fixture.runtime.admitHostTask(append(trace, "second"));
                    }
                }, "admit-during-wake");
                worker.start();
                worker.join();
            }
        });

        fixture.executor.runNext();
        assertListEquals(Arrays.asList("first"), trace, "first wake trace");
        assertEquals(2, fixture.executor.getPostCount(), "admission during wake must re-post");
        fixture.executor.runNext();
        assertListEquals(Arrays.asList("first", "second"), trace, "racing admission delivery");
        pass();
    }

    private void testConcurrentForeignAdmissionStress() throws Exception {
        final Fixture fixture = new Fixture();
        final int producerCount = 4;
        final int tasksPerProducer = 250;
        final java.util.concurrent.atomic.AtomicInteger executed =
                new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<Throwable> producerFailure = new AtomicReference<Throwable>();
        final CountDownLatch start = new CountDownLatch(1);
        final Thread[] producers = new Thread[producerCount];

        for (int producer = 0; producer < producerCount; producer++) {
            producers[producer] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int task = 0; task < tasksPerProducer; task++) {
                            boolean accepted = fixture.runtime.admitHostTask(new RuntimeTask() {
                                @Override
                                public void execute(RuntimeInstance runtime) {
                                    executed.incrementAndGet();
                                }
                            });
                            if (!accepted) {
                                throw new AssertionError("open runtime rejected a stress task");
                            }
                        }
                    } catch (Throwable error) {
                        producerFailure.compareAndSet(null, error);
                    }
                }
            }, "stress-producer-" + producer);
            producers[producer].start();
        }

        start.countDown();
        for (Thread producer : producers) {
            producer.join();
        }

        if (producerFailure.get() != null) {
            throw new AssertionError("foreign producer failed", producerFailure.get());
        }
        assertEquals(1, fixture.executor.getPostCount(), "concurrent idle burst owner posts");
        fixture.executor.runAll();
        assertEquals(
                producerCount * tasksPerProducer,
                executed.get(),
                "concurrent admitted task count");
        pass();
    }

    private void testForeignThreadCannotExecuteRuntimeDirectly() throws Exception {
        final Fixture fixture = new Fixture();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    fixture.runtime.enterHostTurn();
                } catch (Throwable error) {
                    failure.set(error);
                }
            }
        }, "illegal-runtime-owner");
        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException, "foreign direct execution refusal");
        pass();
    }

    private void testHostTaskBudgetRepostsWithoutSplittingCheckpoint() {
        Fixture fixture = new Fixture(1);
        final List<String> trace = new ArrayList<String>();

        fixture.runtime.admitHostTask(new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                trace.add("host-1");
                runtime.queueMicrotask(append(trace, "micro-1"));
            }
        });
        fixture.runtime.admitHostTask(append(trace, "host-2"));

        assertEquals(1, fixture.executor.getPostCount(), "initial budgeted wake");
        fixture.executor.runNext();
        assertListEquals(Arrays.asList("host-1", "micro-1"), trace, "first budgeted wake");
        assertEquals(2, fixture.executor.getPostCount(), "remaining host task must re-post");

        fixture.executor.runNext();
        assertListEquals(
                Arrays.asList("host-1", "micro-1", "host-2"),
                trace,
                "second budgeted wake");
        pass();
    }

    private void testHostTaskFailureReportsAndStillCheckpoints() {
        Fixture fixture = new Fixture();
        final List<String> trace = new ArrayList<String>();
        final IllegalStateException expected = new IllegalStateException("host-boom");

        fixture.runtime.admitHostTask(new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                runtime.queueMicrotask(append(trace, "checkpoint-after-host-error"));
                throw expected;
            }
        });

        fixture.executor.runAll();
        assertListEquals(
                Arrays.asList("checkpoint-after-host-error"),
                trace,
                "host failure checkpoint");
        assertEquals(1, fixture.errors.getEntries().size(), "reported host failures");
        assertSame(
                RuntimeErrorPhase.HOST_TASK,
                fixture.errors.getEntries().get(0).getPhase(),
                "host failure phase");
        assertSame(expected, fixture.errors.getEntries().get(0).getError(), "host error identity");
        pass();
    }

    private void testMicrotaskFailureReportsAndDrainContinues() {
        Fixture fixture = new Fixture();
        final List<String> trace = new ArrayList<String>();
        final IllegalStateException expected = new IllegalStateException("boom");

        fixture.runtime.enterHostTurn();
        try {
            fixture.runtime.queueMicrotask(new RuntimeTask() {
                @Override
                public void execute(RuntimeInstance runtime) {
                    throw expected;
                }
            });
            fixture.runtime.queueMicrotask(append(trace, "after-error"));
        } finally {
            fixture.runtime.leaveHostTurn();
        }

        assertListEquals(Arrays.asList("after-error"), trace, "microtask drain after failure");
        assertEquals(1, fixture.errors.getEntries().size(), "reported microtask failures");
        assertSame(
                RuntimeErrorPhase.MICROTASK,
                fixture.errors.getEntries().get(0).getPhase(),
                "microtask failure phase");
        assertSame(expected, fixture.errors.getEntries().get(0).getError(), "reported error identity");
        pass();
    }

    private void testFatalErrorEscapesAndRestoresSchedulerState() {
        Fixture fixture = new Fixture();
        final List<String> trace = new ArrayList<String>();
        final LinkageError fatal = new LinkageError("fatal");

        fixture.runtime.enterHostTurn();
        fixture.runtime.queueMicrotask(new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                throw fatal;
            }
        });
        fixture.runtime.queueMicrotask(append(trace, "remaining"));

        Throwable observed = null;
        try {
            fixture.runtime.leaveHostTurn();
        } catch (Throwable error) {
            observed = error;
        }

        assertSame(fatal, observed, "fatal error identity");
        assertTrue(!fixture.runtime.isDrainingMicrotasks(), "fatal error must restore drain state");
        assertEquals(0, fixture.errors.getEntries().size(), "fatal error must bypass reporter");

        fixture.runtime.enterHostTurn();
        fixture.runtime.leaveHostTurn();
        assertListEquals(Arrays.asList("remaining"), trace, "remaining queue after fatal error");
        pass();
    }

    private void testCloseDiscardsQueuedTasks() {
        Fixture fixture = new Fixture();
        final int[] discarded = new int[] {0};

        fixture.runtime.admitHostTask(new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                throw new AssertionError("closed runtime executed admitted task");
            }

            @Override
            public void discard() {
                discarded[0]++;
            }
        });

        fixture.runtime.close();
        assertEquals(1, discarded[0], "close discard count");
        fixture.executor.runAll();
        assertEquals(0, fixture.errors.getEntries().size(), "closed wake errors");
        assertTrue(!fixture.runtime.admitHostTask(noOp()), "closed runtime admission result");
        pass();
    }

    private void testCloseDiscardsIdleMicrotasks() {
        Fixture fixture = new Fixture();
        final int[] discarded = new int[] {0};

        fixture.runtime.queueMicrotask(new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                throw new AssertionError("closed runtime executed idle microtask");
            }

            @Override
            public void discard() {
                discarded[0]++;
            }
        });

        fixture.runtime.close();
        assertEquals(1, discarded[0], "idle microtask close discard count");
        fixture.executor.runAll();
        pass();
    }

    private void testRuntimeInstancesAreIsolated() {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        final List<String> trace = new ArrayList<String>();

        first.runtime.queueMicrotask(append(trace, "first"));
        second.runtime.queueMicrotask(append(trace, "second"));

        first.executor.runAll();
        assertListEquals(Arrays.asList("first"), trace, "first runtime isolation");
        second.executor.runAll();
        assertListEquals(Arrays.asList("first", "second"), trace, "second runtime isolation");
        pass();
    }

    private static RuntimeTask append(final List<String> trace, final String value) {
        return new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {
                trace.add(value);
            }
        };
    }

    private static RuntimeTask noOp() {
        return new RuntimeTask() {
            @Override
            public void execute(RuntimeInstance runtime) {}
        };
    }

    private void pass() {
        passed++;
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected same instance");
        }
    }

    private static void assertListEquals(List<String> expected, List<String> actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime;

        Fixture() {
            this(RuntimeInstance.DEFAULT_HOST_TASKS_PER_WAKE);
        }

        Fixture(int hostTasksPerWake) {
            runtime = new RuntimeInstance(executor, errors, hostTasksPerWake);
        }
    }
}
