package io.github.akisarou.jvmwww.web.console.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.console.Console;
import io.github.akisarou.jvmwww.web.console.ConsoleClock;
import io.github.akisarou.jvmwww.web.console.ConsoleLogLevel;
import io.github.akisarou.jvmwww.web.console.ConsoleSink;
import io.github.akisarou.jvmwww.web.console.ConsoleValueFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic conformance for owner-confined Console state and printer boundaries. */
public final class ConsoleConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new ConsoleConformance().run();
    }

    private void run() throws Throwable {
        testLoggingAndRecursiveFormatting();
        testAssertions();
        testCounts();
        testGroupsAndClear();
        testTimers();
        testFormatterBoundary();
        testOwnerConfinement();
        testDiscardSink();
        System.out.println("Web console conformance: " + passed + " tests passed");
    }

    private void testLoggingAndRecursiveFormatting() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Console console = fixture.console(runtime);
            console.log();
            console.log(
                    "%s => %d / %f / %o / %c",
                    "item",
                    "12.9x",
                    "3.25tail",
                    new int[] {1, 2},
                    "color:red",
                    "tail");
            console.info("%s", "%s", "nested");
            console.warn("plain", 7);
        });

        assertEquals(3, fixture.sink.records.size(), "empty logger is ignored");
        assertRecord(
                fixture.sink.records.get(0),
                ConsoleLogLevel.LOG,
                0,
                new Object[] {"item => 12 / 3.25 / [1, 2] / ", "tail"},
                "formatted log");
        assertRecord(
                fixture.sink.records.get(1),
                ConsoleLogLevel.INFO,
                0,
                new Object[] {"nested"},
                "recursive formatting");
        assertRecord(
                fixture.sink.records.get(2),
                ConsoleLogLevel.WARN,
                0,
                new Object[] {"plain", 7},
                "unformatted arguments retained");
        fixture.runtime.close();
        pass();
    }

    private void testAssertions() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Console console = fixture.console(runtime);
            console.assertCondition(true, "not printed");
            console.assertCondition(false);
            console.assertCondition(false, "value=%s", 3);
            console.assertCondition(false, 9, "tail");
        });

        assertEquals(3, fixture.sink.records.size(), "true assertion is ignored");
        assertArguments(
                new Object[] {"Assertion failed"},
                fixture.sink.records.get(0).arguments,
                "empty assertion");
        assertArguments(
                new Object[] {"Assertion failed: value=3"},
                fixture.sink.records.get(1).arguments,
                "string assertion prefix participates in formatting");
        assertArguments(
                new Object[] {"Assertion failed", 9, "tail"},
                fixture.sink.records.get(2).arguments,
                "non-string assertion prefix");
        assertEquals(
                ConsoleLogLevel.ASSERT,
                fixture.sink.records.get(2).level,
                "assert printer level");
        fixture.runtime.close();
        pass();
    }

    private void testCounts() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Console console = fixture.console(runtime);
            console.count();
            console.count();
            console.count("jobs");
            console.countReset();
            console.count();
            console.countReset("missing");
        });

        assertRecordText(fixture.sink.records.get(0), ConsoleLogLevel.COUNT, "default: 1");
        assertRecordText(fixture.sink.records.get(1), ConsoleLogLevel.COUNT, "default: 2");
        assertRecordText(fixture.sink.records.get(2), ConsoleLogLevel.COUNT, "jobs: 1");
        assertRecordText(fixture.sink.records.get(3), ConsoleLogLevel.COUNT, "default: 1");
        assertRecordText(
                fixture.sink.records.get(4),
                ConsoleLogLevel.COUNT_RESET,
                "Count for 'missing' does not exist");
        fixture.runtime.close();
        pass();
    }

    private void testGroupsAndClear() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Console console = fixture.console(runtime);
            console.count("kept");
            console.time("kept");
            console.group("outer");
            console.log("inside");
            console.groupCollapsed();
            console.warn("deep");
            console.groupEnd();
            console.info("middle");
            console.clear();
            console.error("root");
            console.count("kept");
            fixture.clock.advanceNanos(1_000_000L);
            console.timeEnd("kept");
        });

        assertEquals(1, fixture.sink.clearCount, "clear reaches sink");
        assertRecord(
                fixture.sink.records.get(1),
                ConsoleLogLevel.GROUP,
                0,
                new Object[] {"outer"},
                "group start");
        assertRecord(
                fixture.sink.records.get(2),
                ConsoleLogLevel.LOG,
                1,
                new Object[] {"inside"},
                "group child");
        assertRecord(
                fixture.sink.records.get(3),
                ConsoleLogLevel.GROUP_COLLAPSED,
                1,
                new Object[] {"group"},
                "default collapsed label");
        assertRecord(
                fixture.sink.records.get(4),
                ConsoleLogLevel.WARN,
                2,
                new Object[] {"deep"},
                "nested child");
        assertRecord(
                fixture.sink.records.get(5),
                ConsoleLogLevel.INFO,
                1,
                new Object[] {"middle"},
                "group end");
        assertRecord(
                fixture.sink.records.get(6),
                ConsoleLogLevel.ERROR,
                0,
                new Object[] {"root"},
                "clear resets only group stack");
        assertRecordText(fixture.sink.records.get(7), ConsoleLogLevel.COUNT, "kept: 2");
        assertRecordText(
                fixture.sink.records.get(8),
                ConsoleLogLevel.TIME_END,
                "kept: 1.000 ms");
        fixture.runtime.close();
        pass();
    }

    private void testTimers() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Console console = fixture.console(runtime);
            console.time("load");
            fixture.clock.advanceNanos(1_234_567L);
            console.timeLog("load", "phase");
            console.time("load");
            fixture.clock.advanceNanos(2_000_000L);
            console.timeEnd("load");
            console.timeEnd("load");
            console.timeLog("missing");
        });

        assertRecord(
                fixture.sink.records.get(0),
                ConsoleLogLevel.TIME_LOG,
                0,
                new Object[] {"load: 1.234 ms", "phase"},
                "timeLog");
        assertRecordText(
                fixture.sink.records.get(1),
                ConsoleLogLevel.REPORT_WARNING,
                "Timer 'load' already exists");
        assertRecordText(
                fixture.sink.records.get(2),
                ConsoleLogLevel.TIME_END,
                "load: 3.234 ms");
        assertRecordText(
                fixture.sink.records.get(3),
                ConsoleLogLevel.REPORT_WARNING,
                "Timer 'load' does not exist");
        assertRecordText(
                fixture.sink.records.get(4),
                ConsoleLogLevel.REPORT_WARNING,
                "Timer 'missing' does not exist");
        fixture.runtime.close();
        pass();
    }

    private void testFormatterBoundary() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingFormatter formatter = new RecordingFormatter();
        runTurn(fixture.runtime, runtime -> {
            Console console = new Console(runtime, fixture.sink, fixture.clock, formatter);
            console.debug("%s %d %f %o %O %c", "a", 2, 3, 4, 5, "css");
        });

        assertArguments(
                new Object[] {"S(a) I(2) F(3) U(4) G(5) "},
                fixture.sink.records.get(0).arguments,
                "formatter boundary");
        assertEquals(
                Arrays.asList("s", "i", "f", "u", "g"),
                formatter.calls,
                "conversion order");
        fixture.runtime.close();
        pass();
    }

    private void testOwnerConfinement() throws Throwable {
        Fixture fixture = new Fixture();
        Console[] console = new Console[1];
        runTurn(fixture.runtime, runtime -> console[0] = fixture.console(runtime));
        assertThrows(
                IllegalStateException.class,
                () -> console[0].log("outside"),
                "console outside a host turn");
        fixture.runtime.close();
        pass();
    }

    private void testDiscardSink() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Console console = new Console(runtime);
            console.log("discarded");
            console.group();
            console.clear();
        });
        fixture.runtime.close();
        pass();
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try {
            task.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    private void pass() {
        passed++;
    }

    private static void assertRecordText(
            Record record,
            ConsoleLogLevel level,
            String text) {
        assertRecord(record, level, 0, new Object[] {text}, text);
    }

    private static void assertRecord(
            Record record,
            ConsoleLogLevel level,
            int depth,
            Object[] arguments,
            String label) {
        assertEquals(level, record.level, label + " level");
        assertEquals(depth, record.depth, label + " depth");
        assertArguments(arguments, record.arguments, label + " arguments");
    }

    private static void assertArguments(Object[] expected, Object[] actual, String label) {
        if (!Arrays.deepEquals(expected, actual)) {
            throw new AssertionError(
                    label
                            + ": expected "
                            + Arrays.deepToString(expected)
                            + ", got "
                            + Arrays.deepToString(actual));
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertThrows(
            Class<? extends Throwable> expected,
            ThrowingRunnable action,
            String label) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(label + ": wrong exception " + error, error);
        }
        throw new AssertionError(label + ": expected " + expected.getName());
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
        final RecordingSink sink = new RecordingSink();
        final ManualClock clock = new ManualClock();

        Console console(RuntimeInstance owner) {
            return new Console(owner, sink, clock);
        }
    }

    private static final class ManualClock implements ConsoleClock {
        private long nowNanos;

        @Override
        public long nowNanos() {
            return nowNanos;
        }

        void advanceNanos(long delta) {
            nowNanos += delta;
        }
    }

    private static final class RecordingSink implements ConsoleSink {
        final List<Record> records = new ArrayList<Record>();
        int clearCount;

        @Override
        public void print(ConsoleLogLevel level, int groupDepth, Object[] arguments) {
            records.add(new Record(level, groupDepth, arguments.clone()));
        }

        @Override
        public void clear() {
            clearCount++;
        }
    }

    private static final class Record {
        final ConsoleLogLevel level;
        final int depth;
        final Object[] arguments;

        Record(ConsoleLogLevel level, int depth, Object[] arguments) {
            this.level = level;
            this.depth = depth;
            this.arguments = arguments;
        }
    }

    private static final class RecordingFormatter implements ConsoleValueFormatter {
        final List<String> calls = new ArrayList<String>();

        @Override
        public String toStringValue(Object value) {
            calls.add("s");
            return "S(" + value + ")";
        }

        @Override
        public String toIntegerString(Object value) {
            calls.add("i");
            return "I(" + value + ")";
        }

        @Override
        public String toFloatString(Object value) {
            calls.add("f");
            return "F(" + value + ")";
        }

        @Override
        public String formatObject(Object value, boolean generic) {
            calls.add(generic ? "g" : "u");
            return (generic ? "G(" : "U(") + value + ")";
        }
    }
}
