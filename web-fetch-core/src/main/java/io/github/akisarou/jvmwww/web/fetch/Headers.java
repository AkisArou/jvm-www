package io.github.akisarou.jvmwww.web.fetch;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Owner-confined buffered Headers implementation for the selected Fetch profile. */
public final class Headers {
    private final RuntimeInstance runtime;
    private final ArrayList<Entry> entries;
    private boolean immutable;

    public Headers(RuntimeInstance runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        FetchRuntimeChecks.assertLanguageExecution(runtime);
        this.entries = new ArrayList<Entry>();
    }

    public Headers(Headers source) {
        Objects.requireNonNull(source, "source");
        source.assertAccess();
        this.runtime = source.runtime;
        this.entries = new ArrayList<Entry>(source.entries.size());
        for (Entry entry : source.entries) {
            this.entries.add(new Entry(entry.name, entry.value));
        }
    }

    Headers(RuntimeInstance runtime, String[] names, String[] values, boolean immutable) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        FetchRuntimeChecks.assertLanguageExecution(runtime);
        if (names.length != values.length) {
            throw new IllegalArgumentException("Header name/value arrays differ in length");
        }
        this.entries = new ArrayList<Entry>(names.length);
        for (int i = 0; i < names.length; i++) {
            appendInternal(names[i], values[i]);
        }
        this.immutable = immutable;
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public void append(String name, String value) {
        assertMutable();
        appendInternal(name, value);
    }

    public void set(String name, String value) {
        assertMutable();
        String normalizedName = normalizeName(name);
        String normalizedValue = normalizeValue(value);
        deleteInternal(normalizedName);
        entries.add(new Entry(normalizedName, normalizedValue));
    }

    public void delete(String name) {
        assertMutable();
        deleteInternal(normalizeName(name));
    }

    public boolean has(String name) {
        assertAccess();
        String normalized = normalizeName(name);
        for (Entry entry : entries) {
            if (entry.name.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public String get(String name) {
        assertAccess();
        String normalized = normalizeName(name);
        StringBuilder combined = null;
        for (Entry entry : entries) {
            if (!entry.name.equals(normalized)) {
                continue;
            }
            if (combined == null) {
                combined = new StringBuilder(entry.value);
            } else {
                combined.append(", ").append(entry.value);
            }
        }
        return combined == null ? null : combined.toString();
    }

    public int size() {
        assertAccess();
        return entries.size();
    }

    public String getName(int index) {
        assertAccess();
        return entries.get(index).name;
    }

    public String getValue(int index) {
        assertAccess();
        return entries.get(index).value;
    }

    Headers immutableCopy() {
        Headers copy = new Headers(this);
        copy.immutable = true;
        return copy;
    }

    String[] snapshotNames() {
        assertAccess();
        String[] result = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            result[i] = entries.get(i).name;
        }
        return result;
    }

    String[] snapshotValues() {
        assertAccess();
        String[] result = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            result[i] = entries.get(i).value;
        }
        return result;
    }

    private void appendInternal(String name, String value) {
        entries.add(new Entry(normalizeName(name), normalizeValue(value)));
    }

    private void deleteInternal(String normalizedName) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).name.equals(normalizedName)) {
                entries.remove(i);
            }
        }
    }

    private void assertAccess() {
        FetchRuntimeChecks.assertLanguageExecution(runtime);
    }

    private void assertMutable() {
        assertAccess();
        if (immutable) {
            throw new JsTypeError("Headers are immutable");
        }
    }

    private static String normalizeName(String name) {
        String checked = Objects.requireNonNull(name, "name");
        if (checked.isEmpty()) {
            throw new JsTypeError("Header name must not be empty");
        }
        for (int i = 0; i < checked.length(); i++) {
            char c = checked.charAt(i);
            if (!isTokenChar(c)) {
                throw new JsTypeError("Invalid HTTP header name: " + checked);
            }
        }
        return checked.toLowerCase(Locale.ROOT);
    }

    private static String normalizeValue(String value) {
        String checked = Objects.requireNonNull(value, "value");
        for (int i = 0; i < checked.length(); i++) {
            char c = checked.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new JsTypeError("Invalid HTTP header value");
            }
        }
        int start = 0;
        int end = checked.length();
        while (start < end && isHttpWhitespace(checked.charAt(start))) {
            start++;
        }
        while (end > start && isHttpWhitespace(checked.charAt(end - 1))) {
            end--;
        }
        return checked.substring(start, end);
    }

    private static boolean isHttpWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isTokenChar(char c) {
        if ((c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')) {
            return true;
        }
        switch (c) {
            case '!': case '#': case '$': case '%': case '&': case '\'': case '*': case '+':
            case '-': case '.': case '^': case '_': case '`': case '|': case '~':
                return true;
            default:
                return false;
        }
    }

    private static final class Entry {
        final String name;
        final String value;

        Entry(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
