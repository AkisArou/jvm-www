package io.github.akisarou.jvmwww.web.url;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.ArrayList;
import java.util.Objects;

/** Owner-confined WHATWG URLSearchParams list and form serializer. */
public final class URLSearchParams implements FormUrlEncodedConsumer {
    private final RuntimeInstance runtime;
    private final ArrayList<Entry> entries;
    private URLSearchParamsUpdateTarget updateTarget;

    public URLSearchParams(RuntimeInstance runtime) {
        this(runtime, "", null);
    }

    public URLSearchParams(RuntimeInstance runtime, String init) {
        this(runtime, init, null);
    }

    public URLSearchParams(URLSearchParams source) {
        Objects.requireNonNull(source, "source");
        source.assertAccess();
        this.runtime = source.runtime;
        this.entries = new ArrayList<Entry>(source.entries.size());
        for (Entry entry : source.entries) {
            entries.add(new Entry(entry.name, entry.value));
        }
    }

    URLSearchParams(
            RuntimeInstance runtime,
            String init,
            URLSearchParamsUpdateTarget updateTarget) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        UrlRuntimeChecks.assertLanguageExecution(runtime);
        this.entries = new ArrayList<Entry>();
        this.updateTarget = updateTarget;
        FormUrlCodec.parse(runtime, Objects.requireNonNull(init, "init"), this);
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    @Override
    public void acceptFormEntry(String name, String value) {
        assertAccess();
        entries.add(new Entry(
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(value, "value")));
    }

    public int size() {
        assertAccess();
        return entries.size();
    }

    public void append(String name, String value) {
        assertAccess();
        entries.add(new Entry(scalar(name, "name"), scalar(value, "value")));
        update();
    }

    public void delete(String name) {
        assertAccess();
        String checkedName = scalar(name, "name");
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (entries.get(index).name.equals(checkedName)) entries.remove(index);
        }
        update();
    }

    public void delete(String name, String value) {
        assertAccess();
        String checkedName = scalar(name, "name");
        String checkedValue = scalar(value, "value");
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (entry.name.equals(checkedName) && entry.value.equals(checkedValue)) {
                entries.remove(index);
            }
        }
        update();
    }

    public String get(String name) {
        assertAccess();
        String checkedName = scalar(name, "name");
        for (Entry entry : entries) {
            if (entry.name.equals(checkedName)) return entry.value;
        }
        return null;
    }

    public String[] getAll(String name) {
        assertAccess();
        String checkedName = scalar(name, "name");
        int count = 0;
        for (Entry entry : entries) {
            if (entry.name.equals(checkedName)) count++;
        }
        String[] result = new String[count];
        int written = 0;
        for (Entry entry : entries) {
            if (entry.name.equals(checkedName)) result[written++] = entry.value;
        }
        return result;
    }

    public boolean has(String name) {
        assertAccess();
        String checkedName = scalar(name, "name");
        for (Entry entry : entries) {
            if (entry.name.equals(checkedName)) return true;
        }
        return false;
    }

    public boolean has(String name, String value) {
        assertAccess();
        String checkedName = scalar(name, "name");
        String checkedValue = scalar(value, "value");
        for (Entry entry : entries) {
            if (entry.name.equals(checkedName) && entry.value.equals(checkedValue)) return true;
        }
        return false;
    }

    public void set(String name, String value) {
        assertAccess();
        String checkedName = scalar(name, "name");
        String checkedValue = scalar(value, "value");
        int first = -1;
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (!entry.name.equals(checkedName)) continue;
            if (first < 0) {
                first = index;
                entry.value = checkedValue;
            } else {
                entries.remove(index--);
            }
        }
        if (first < 0) entries.add(new Entry(checkedName, checkedValue));
        update();
    }

    /** Stable sort by UTF-16 code units, matching the URL Standard. */
    public void sort() {
        assertAccess();
        for (int index = 1; index < entries.size(); index++) {
            Entry current = entries.get(index);
            int insertion = index;
            while (insertion > 0
                    && entries.get(insertion - 1).name.compareTo(current.name) > 0) {
                entries.set(insertion, entries.get(insertion - 1));
                insertion--;
            }
            entries.set(insertion, current);
        }
        update();
    }

    /** Compiler-facing iteration ABI preserving pair insertion order. */
    public String getName(int index) {
        assertAccess();
        return entries.get(index).name;
    }

    /** Compiler-facing iteration ABI preserving pair insertion order. */
    public String getValue(int index) {
        assertAccess();
        return entries.get(index).value;
    }

    /**
     * Compiler/Fetch-facing exact form body serialization. The returned array is newly allocated
     * and contains only ASCII application/x-www-form-urlencoded bytes.
     */
    public byte[] copyFormEncodedBytes() {
        assertAccess();
        return FormUrlCodec.serializeBytes(entries);
    }

    @Override
    public String toString() {
        assertAccess();
        return serialize();
    }

    void replaceFromQuery(String query) {
        assertAccess();
        entries.clear();
        FormUrlCodec.parse(runtime, query == null ? "" : query, this);
    }

    void setUpdateTarget(URLSearchParamsUpdateTarget updateTarget) {
        assertAccess();
        this.updateTarget = updateTarget;
    }

    String serialize() {
        return FormUrlCodec.serialize(runtime, entries);
    }

    private void update() {
        if (updateTarget != null) updateTarget.updateFromSearchParams(this);
    }

    private void assertAccess() {
        UrlRuntimeChecks.assertLanguageExecution(runtime);
    }

    private static String scalar(String value, String label) {
        return UrlScalar.fromString(value, label);
    }

    static final class Entry {
        final String name;
        String value;

        Entry(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
