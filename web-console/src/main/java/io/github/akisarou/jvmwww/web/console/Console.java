package io.github.akisarou.jvmwww.web.console;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Objects;

/** Owner-confined Console Standard state with a synchronous pluggable printer. */
public final class Console {
    private static final String DEFAULT_LABEL = "default";
    private static final String DEFAULT_GROUP_LABEL = "group";

    private final RuntimeInstance runtime;
    private final ConsoleSink sink;
    private final ConsoleClock clock;
    private final ConsoleValueFormatter valueFormatter;

    private CountEntry counts;
    private TimerEntry timers;
    private int groupDepth;

    public Console(RuntimeInstance runtime) {
        this(runtime, ConsoleSink.DISCARD);
    }

    public Console(RuntimeInstance runtime, ConsoleSink sink) {
        this(runtime, sink, ConsoleClock.SYSTEM, DefaultConsoleValueFormatter.INSTANCE);
    }

    public Console(RuntimeInstance runtime, ConsoleSink sink, ConsoleClock clock) {
        this(runtime, sink, clock, DefaultConsoleValueFormatter.INSTANCE);
    }

    public Console(
            RuntimeInstance runtime,
            ConsoleSink sink,
            ConsoleClock clock,
            ConsoleValueFormatter valueFormatter) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        ConsoleRuntimeChecks.assertLanguageExecution(runtime);
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.valueFormatter = Objects.requireNonNull(valueFormatter, "valueFormatter");
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    /** JVM ABI for the JavaScript {@code console.assert} method. */
    public void assertCondition(boolean condition, Object... data) {
        assertAccess();
        Object[] checked = requireArguments(data);
        if (condition) return;

        Object[] message;
        if (checked.length == 0) {
            message = new Object[] {"Assertion failed"};
        } else if (checked[0] instanceof String) {
            message = checked.clone();
            message[0] = "Assertion failed: " + checked[0];
        } else {
            message = new Object[checked.length + 1];
            message[0] = "Assertion failed";
            System.arraycopy(checked, 0, message, 1, checked.length);
        }
        logger(ConsoleLogLevel.ASSERT, message);
    }

    public void clear() {
        assertAccess();
        groupDepth = 0;
        sink.clear();
    }

    public void debug(Object... data) {
        logAt(ConsoleLogLevel.DEBUG, data);
    }

    public void error(Object... data) {
        logAt(ConsoleLogLevel.ERROR, data);
    }

    public void info(Object... data) {
        logAt(ConsoleLogLevel.INFO, data);
    }

    public void log(Object... data) {
        logAt(ConsoleLogLevel.LOG, data);
    }

    public void warn(Object... data) {
        logAt(ConsoleLogLevel.WARN, data);
    }

    public void count() {
        count(DEFAULT_LABEL);
    }

    public void count(String label) {
        assertAccess();
        String checked = Objects.requireNonNull(label, "label");
        CountEntry entry = findCount(checked);
        if (entry == null) {
            entry = new CountEntry(checked, counts);
            counts = entry;
        }
        if (entry.value == Long.MAX_VALUE) {
            throw new IllegalStateException("Console count exhausted for label: " + checked);
        }
        entry.value++;
        logger(
                ConsoleLogLevel.COUNT,
                new Object[] {checked + ": " + Long.toString(entry.value)});
    }

    public void countReset() {
        countReset(DEFAULT_LABEL);
    }

    public void countReset(String label) {
        assertAccess();
        String checked = Objects.requireNonNull(label, "label");
        CountEntry entry = findCount(checked);
        if (entry != null) {
            entry.value = 0L;
            return;
        }
        logger(
                ConsoleLogLevel.COUNT_RESET,
                new Object[] {"Count for '" + checked + "' does not exist"});
    }

    public void group(Object... data) {
        startGroup(ConsoleLogLevel.GROUP, data);
    }

    public void groupCollapsed(Object... data) {
        startGroup(ConsoleLogLevel.GROUP_COLLAPSED, data);
    }

    public void groupEnd() {
        assertAccess();
        if (groupDepth > 0) groupDepth--;
    }

    public void time() {
        time(DEFAULT_LABEL);
    }

    public void time(String label) {
        assertAccess();
        String checked = Objects.requireNonNull(label, "label");
        if (findTimer(checked) != null) {
            reportWarning("Timer '" + checked + "' already exists");
            return;
        }
        timers = new TimerEntry(checked, clock.nowNanos(), timers);
    }

    public void timeLog() {
        timeLog(DEFAULT_LABEL);
    }

    public void timeLog(String label, Object... data) {
        assertAccess();
        String checked = Objects.requireNonNull(label, "label");
        Object[] checkedData = requireArguments(data);
        TimerEntry entry = findTimer(checked);
        if (entry == null) {
            reportWarning("Timer '" + checked + "' does not exist");
            return;
        }
        logger(
                ConsoleLogLevel.TIME_LOG,
                prependTimer(checked, entry.startedNanos, checkedData));
    }

    public void timeEnd() {
        timeEnd(DEFAULT_LABEL);
    }

    public void timeEnd(String label) {
        assertAccess();
        String checked = Objects.requireNonNull(label, "label");
        TimerEntry previous = null;
        TimerEntry entry = timers;
        while (entry != null && !entry.label.equals(checked)) {
            previous = entry;
            entry = entry.next;
        }
        if (entry == null) {
            reportWarning("Timer '" + checked + "' does not exist");
            return;
        }
        if (previous == null) {
            timers = entry.next;
        } else {
            previous.next = entry.next;
        }
        logger(
                ConsoleLogLevel.TIME_END,
                new Object[] {timerPrefix(checked, entry.startedNanos)});
    }

    private void logAt(ConsoleLogLevel level, Object[] data) {
        assertAccess();
        logger(level, requireArguments(data));
    }

    private void startGroup(ConsoleLogLevel level, Object[] data) {
        assertAccess();
        Object[] checked = requireArguments(data);
        if (groupDepth == Integer.MAX_VALUE) {
            throw new IllegalStateException("Console group nesting exhausted");
        }
        logger(level, checked.length == 0 ? new Object[] {DEFAULT_GROUP_LABEL} : checked);
        groupDepth++;
    }

    private void reportWarning(String message) {
        logger(ConsoleLogLevel.REPORT_WARNING, new Object[] {message});
    }

    private Object[] prependTimer(String label, long startedNanos, Object[] data) {
        Object[] result = new Object[data.length + 1];
        result[0] = timerPrefix(label, startedNanos);
        System.arraycopy(data, 0, result, 1, data.length);
        return result;
    }

    private String timerPrefix(String label, long startedNanos) {
        long elapsed = clock.nowNanos() - startedNanos;
        if (elapsed < 0L) elapsed = 0L;
        long wholeMilliseconds = elapsed / 1_000_000L;
        long fractionalMilliseconds = (elapsed % 1_000_000L) / 1_000L;
        StringBuilder result = new StringBuilder(label.length() + 24);
        result.append(label).append(": ").append(wholeMilliseconds).append('.');
        if (fractionalMilliseconds < 100L) result.append('0');
        if (fractionalMilliseconds < 10L) result.append('0');
        result.append(fractionalMilliseconds).append(" ms");
        return result.toString();
    }

    private void logger(ConsoleLogLevel level, Object[] arguments) {
        if (arguments.length == 0) return;
        Object[] processed = arguments;
        if (arguments.length > 1 && arguments[0] instanceof String) {
            processed = format(arguments);
        }
        sink.print(level, groupDepth, processed);
    }

    private Object[] format(Object[] arguments) {
        String target = (String) arguments[0];
        int nextArgument = 1;
        boolean changed = false;
        while (nextArgument < arguments.length) {
            int specifier = findSpecifier(target);
            if (specifier < 0) break;
            char kind = target.charAt(specifier + 1);
            Object current = arguments[nextArgument++];
            String converted;
            switch (kind) {
                case 's':
                    converted = valueFormatter.toStringValue(current);
                    break;
                case 'd':
                case 'i':
                    converted = valueFormatter.toIntegerString(current);
                    break;
                case 'f':
                    converted = valueFormatter.toFloatString(current);
                    break;
                case 'o':
                    converted = valueFormatter.formatObject(current, false);
                    break;
                case 'O':
                    converted = valueFormatter.formatObject(current, true);
                    break;
                case 'c':
                    converted = "";
                    break;
                default:
                    throw new AssertionError("Unknown Console format specifier: " + kind);
            }
            converted = Objects.requireNonNull(converted, "Console formatter returned null");
            target =
                    target.substring(0, specifier)
                            + converted
                            + target.substring(specifier + 2);
            changed = true;
        }
        if (!changed) return arguments;

        int remaining = arguments.length - nextArgument;
        Object[] result = new Object[remaining + 1];
        result[0] = target;
        System.arraycopy(arguments, nextArgument, result, 1, remaining);
        return result;
    }

    private static int findSpecifier(String target) {
        for (int index = 0; index + 1 < target.length(); index++) {
            if (target.charAt(index) != '%') continue;
            switch (target.charAt(index + 1)) {
                case 's':
                case 'd':
                case 'i':
                case 'f':
                case 'o':
                case 'O':
                case 'c':
                    return index;
                default:
                    break;
            }
        }
        return -1;
    }

    private CountEntry findCount(String label) {
        CountEntry entry = counts;
        while (entry != null) {
            if (entry.label.equals(label)) return entry;
            entry = entry.next;
        }
        return null;
    }

    private TimerEntry findTimer(String label) {
        TimerEntry entry = timers;
        while (entry != null) {
            if (entry.label.equals(label)) return entry;
            entry = entry.next;
        }
        return null;
    }

    private void assertAccess() {
        ConsoleRuntimeChecks.assertLanguageExecution(runtime);
    }

    private static Object[] requireArguments(Object[] data) {
        return Objects.requireNonNull(data, "data");
    }

    private static final class CountEntry {
        final String label;
        final CountEntry next;
        long value;

        CountEntry(String label, CountEntry next) {
            this.label = label;
            this.next = next;
        }
    }

    private static final class TimerEntry {
        final String label;
        final long startedNanos;
        TimerEntry next;

        TimerEntry(String label, long startedNanos, TimerEntry next) {
            this.label = label;
            this.startedNanos = startedNanos;
            this.next = next;
        }
    }
}
