package io.github.akisarou.jvmwww.web.console.android.testkit;

import android.util.Log;
import io.github.akisarou.jvmwww.web.console.ConsoleLogLevel;
import io.github.akisarou.jvmwww.web.console.ConsoleValueFormatter;
import io.github.akisarou.jvmwww.web.console.DefaultConsoleValueFormatter;
import io.github.akisarou.jvmwww.web.console.android.AndroidLogConsoleSink;

/** Deterministic conformance for the Android Logcat Console destination. */
public final class AndroidLogConsoleSinkConformance {
    private int passed;

    public static void main(String[] args) {
        new AndroidLogConsoleSinkConformance().run();
    }

    private void run() {
        testEveryLevelMappingIsSynchronous();
        testGroupDepthAndArgumentRendering();
        testCustomFormatterAndTransientArguments();
        testClearAndInvalidInputs();
        System.out.println("Android Console sink conformance: " + passed + " tests passed");
    }

    private void testEveryLevelMappingIsSynchronous() {
        Log.resetForTest();
        AndroidLogConsoleSink sink = new AndroidLogConsoleSink("jvm-www");
        ConsoleLogLevel[] levels = ConsoleLogLevel.values();
        int[] priorities = {
            Log.ERROR,
            Log.DEBUG,
            Log.ERROR,
            Log.INFO,
            Log.INFO,
            Log.WARN,
            Log.INFO,
            Log.WARN,
            Log.INFO,
            Log.INFO,
            Log.INFO,
            Log.INFO,
            Log.WARN
        };
        assertEquals(levels.length, priorities.length, "mapping fixture length");
        for (int index = 0; index < levels.length; index++) {
            sink.print(levels[index], 0, new Object[] {levels[index].name()});
            assertEquals(index + 1, Log.getEntryCountForTest(), "delivered before return " + levels[index]);
            assertEquals(priorities[index], Log.getPriorityForTest(index), "priority " + levels[index]);
            assertEquals("jvm-www", Log.getTagForTest(index), "tag " + levels[index]);
            String expectedMessage =
                    levels[index] == ConsoleLogLevel.GROUP_COLLAPSED
                            ? "[collapsed] " + levels[index].name()
                            : levels[index].name();
            assertEquals(expectedMessage, Log.getMessageForTest(index), "message " + levels[index]);
        }
        assertEquals("jvm-www", sink.getTag(), "tag getter");
        assertSame(DefaultConsoleValueFormatter.INSTANCE, sink.getValueFormatter(), "default formatter");
        pass();
    }

    private void testGroupDepthAndArgumentRendering() {
        Log.resetForTest();
        AndroidLogConsoleSink sink = new AndroidLogConsoleSink("groups");
        Object[] arguments = new Object[] {"alpha", Integer.valueOf(3), new int[] {1, 2}};
        sink.print(ConsoleLogLevel.LOG, 2, arguments);
        sink.print(ConsoleLogLevel.GROUP_COLLAPSED, 1, new Object[] {"network"});
        assertEquals("    alpha 3 [1, 2]", Log.getMessageForTest(0), "group indentation");
        assertEquals("  [collapsed] network", Log.getMessageForTest(1), "collapsed marker");
        assertEquals("alpha", arguments[0], "arguments not mutated");
        assertEquals(3, ((Integer) arguments[1]).intValue(), "number not mutated");
        pass();
    }

    private void testCustomFormatterAndTransientArguments() {
        Log.resetForTest();
        RecordingFormatter formatter = new RecordingFormatter();
        AndroidLogConsoleSink sink = new AndroidLogConsoleSink("custom", formatter);
        Object marker = new Object();
        Object[] arguments = new Object[] {marker, null};
        sink.print(ConsoleLogLevel.INFO, 0, arguments);
        assertEquals("<object> <null>", Log.getMessageForTest(0), "custom rendering");
        assertEquals(2, formatter.calls, "one render per argument");
        assertSame(marker, arguments[0], "marker retained by caller only");
        assertTrue(arguments[1] == null, "null argument unchanged");
        pass();
    }

    private void testClearAndInvalidInputs() {
        Log.resetForTest();
        AndroidLogConsoleSink sink = new AndroidLogConsoleSink("clear");
        sink.print(ConsoleLogLevel.LOG, 0, new Object[] {"before"});
        sink.clear();
        assertEquals(1, Log.getEntryCountForTest(), "clear does not erase process Logcat");

        assertThrows(NullPointerException.class, () -> new AndroidLogConsoleSink(null), "null tag");
        assertThrows(
                NullPointerException.class,
                () -> new AndroidLogConsoleSink("tag", null),
                "null formatter");
        assertThrows(
                NullPointerException.class,
                () -> sink.print(null, 0, new Object[] {"message"}),
                "null level");
        assertThrows(
                NullPointerException.class,
                () -> sink.print(ConsoleLogLevel.LOG, 0, null),
                "null argument array");
        assertThrows(
                IllegalArgumentException.class,
                () -> sink.print(ConsoleLogLevel.LOG, -1, new Object[] {"message"}),
                "negative group depth");
        AndroidLogConsoleSink badFormatter =
                new AndroidLogConsoleSink("bad", new NullFormatter());
        assertThrows(
                NullPointerException.class,
                () -> badFormatter.print(ConsoleLogLevel.LOG, 0, new Object[] {"message"}),
                "null rendered value");
        pass();
    }

    private void pass() { passed++; }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(String expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": objects differ");
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

    private interface ThrowingRunnable { void run() throws Throwable; }

    private static final class RecordingFormatter implements ConsoleValueFormatter {
        int calls;
        @Override public String toStringValue(Object value) { return render(value); }
        @Override public String toIntegerString(Object value) { return render(value); }
        @Override public String toFloatString(Object value) { return render(value); }
        @Override public String formatObject(Object value, boolean generic) { return render(value); }
        private String render(Object value) {
            calls++;
            return value == null ? "<null>" : "<object>";
        }
    }

    private static final class NullFormatter implements ConsoleValueFormatter {
        @Override public String toStringValue(Object value) { return null; }
        @Override public String toIntegerString(Object value) { return null; }
        @Override public String toFloatString(Object value) { return null; }
        @Override public String formatObject(Object value, boolean generic) { return null; }
    }
}
