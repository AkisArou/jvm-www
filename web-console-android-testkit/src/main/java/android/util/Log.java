package android.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic test-only Android Log primitive; never packaged with production code. */
public final class Log {
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();

    private Log() {}

    public static int d(String tag, String message) { return record(DEBUG, tag, message); }
    public static int i(String tag, String message) { return record(INFO, tag, message); }
    public static int w(String tag, String message) { return record(WARN, tag, message); }
    public static int e(String tag, String message) { return record(ERROR, tag, message); }

    public static void resetForTest() { ENTRIES.clear(); }
    public static int getEntryCountForTest() { return ENTRIES.size(); }
    public static int getPriorityForTest(int index) { return ENTRIES.get(index).priority; }
    public static String getTagForTest(int index) { return ENTRIES.get(index).tag; }
    public static String getMessageForTest(int index) { return ENTRIES.get(index).message; }

    private static int record(int priority, String tag, String message) {
        ENTRIES.add(new Entry(
                priority,
                Objects.requireNonNull(tag, "tag"),
                Objects.requireNonNull(message, "message")));
        return ENTRIES.size();
    }

    private static final class Entry {
        final int priority;
        final String tag;
        final String message;

        Entry(int priority, String tag, String message) {
            this.priority = priority;
            this.tag = tag;
            this.message = message;
        }
    }
}
