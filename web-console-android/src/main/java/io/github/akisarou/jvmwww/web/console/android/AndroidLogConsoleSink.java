package io.github.akisarou.jvmwww.web.console.android;

import android.util.Log;
import io.github.akisarou.jvmwww.web.console.ConsoleLogLevel;
import io.github.akisarou.jvmwww.web.console.ConsoleSink;
import io.github.akisarou.jvmwww.web.console.ConsoleValueFormatter;
import io.github.akisarou.jvmwww.web.console.DefaultConsoleValueFormatter;
import java.util.Objects;

/** Synchronous Android Logcat destination for one processed Console printer call. */
public final class AndroidLogConsoleSink implements ConsoleSink {
    private static final String COLLAPSED_PREFIX = "[collapsed] ";

    private final String tag;
    private final ConsoleValueFormatter valueFormatter;

    public AndroidLogConsoleSink(String tag) {
        this(tag, DefaultConsoleValueFormatter.INSTANCE);
    }

    public AndroidLogConsoleSink(String tag, ConsoleValueFormatter valueFormatter) {
        this.tag = Objects.requireNonNull(tag, "tag");
        this.valueFormatter = Objects.requireNonNull(valueFormatter, "valueFormatter");
    }

    public String getTag() {
        return tag;
    }

    public ConsoleValueFormatter getValueFormatter() {
        return valueFormatter;
    }

    @Override
    public void print(ConsoleLogLevel level, int groupDepth, Object[] arguments) {
        ConsoleLogLevel checkedLevel = Objects.requireNonNull(level, "level");
        Object[] checkedArguments = Objects.requireNonNull(arguments, "arguments");
        if (groupDepth < 0) {
            throw new IllegalArgumentException("Console group depth must be non-negative");
        }

        String message = render(checkedLevel, groupDepth, checkedArguments);
        switch (checkedLevel) {
            case DEBUG:
                Log.d(tag, message);
                break;
            case ASSERT:
            case ERROR:
                Log.e(tag, message);
                break;
            case WARN:
            case COUNT_RESET:
            case REPORT_WARNING:
                Log.w(tag, message);
                break;
            case INFO:
            case LOG:
            case COUNT:
            case GROUP:
            case GROUP_COLLAPSED:
            case TIME_LOG:
            case TIME_END:
                Log.i(tag, message);
                break;
            default:
                throw new AssertionError("Unknown Console level: " + checkedLevel);
        }
    }

    /** Logcat has no application-scoped clear operation, so this destination deliberately no-ops. */
    @Override
    public void clear() {}

    private String render(ConsoleLogLevel level, int groupDepth, Object[] arguments) {
        int estimatedLength = groupDepth * 2 + (level == ConsoleLogLevel.GROUP_COLLAPSED ? 12 : 0);
        StringBuilder result = new StringBuilder(estimatedLength + arguments.length * 8);
        for (int index = 0; index < groupDepth; index++) result.append("  ");
        if (level == ConsoleLogLevel.GROUP_COLLAPSED) result.append(COLLAPSED_PREFIX);
        for (int index = 0; index < arguments.length; index++) {
            if (index != 0) result.append(' ');
            String rendered = valueFormatter.formatObject(arguments[index], false);
            result.append(Objects.requireNonNull(rendered, "Console value formatter returned null"));
        }
        return result.toString();
    }
}
