package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.url.FormUrlEncodedConsumer;
import java.util.Arrays;
import java.util.Objects;

/** Owner-confined ordered FormData entry list. Values are scalar strings or File objects. */
public final class FormData implements BufferedBodySource, FormUrlEncodedConsumer {
    private static final byte KIND_STRING = 1;
    private static final byte KIND_FILE = 2;

    private final RuntimeInstance runtime;
    private final MultipartBoundarySource boundarySource;
    private String[] names = new String[4];
    private Object[] values = new Object[4];
    private byte[] kinds = new byte[4];
    private int size;

    public FormData(RuntimeInstance runtime) {
        this(runtime, DefaultMultipartBoundarySource.INSTANCE);
    }

    public FormData(RuntimeInstance runtime, MultipartBoundarySource boundarySource) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        BodyRuntimeChecks.assertLanguageExecution(runtime);
        this.boundarySource = Objects.requireNonNull(boundarySource, "boundarySource");
    }

    @Override
    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public int size() {
        assertAccess();
        return size;
    }

    public void append(String name, String value) {
        assertAccess();
        appendEntry(
                BodyScalar.fromString(name, "name"),
                BodyScalar.fromString(value, "value"),
                KIND_STRING);
    }

    public void append(String name, Blob value) {
        append(name, value, null, false);
    }

    public void append(String name, Blob value, String filename) {
        append(name, value, filename, true);
    }

    public void delete(String name) {
        assertAccess();
        String checkedName = BodyScalar.fromString(name, "name");
        int write = 0;
        for (int read = 0; read < size; read++) {
            if (names[read].equals(checkedName)) continue;
            if (write != read) {
                names[write] = names[read];
                values[write] = values[read];
                kinds[write] = kinds[read];
            }
            write++;
        }
        clearRange(write, size);
        size = write;
    }

    public Object get(String name) {
        assertAccess();
        String checkedName = BodyScalar.fromString(name, "name");
        for (int index = 0; index < size; index++) {
            if (names[index].equals(checkedName)) return values[index];
        }
        return null;
    }

    public Object[] getAll(String name) {
        assertAccess();
        String checkedName = BodyScalar.fromString(name, "name");
        int count = 0;
        for (int index = 0; index < size; index++) {
            if (names[index].equals(checkedName)) count++;
        }
        Object[] result = new Object[count];
        int written = 0;
        for (int index = 0; index < size; index++) {
            if (names[index].equals(checkedName)) result[written++] = values[index];
        }
        return result;
    }

    public boolean has(String name) {
        assertAccess();
        String checkedName = BodyScalar.fromString(name, "name");
        for (int index = 0; index < size; index++) {
            if (names[index].equals(checkedName)) return true;
        }
        return false;
    }

    public void set(String name, String value) {
        assertAccess();
        replaceEntries(
                BodyScalar.fromString(name, "name"),
                BodyScalar.fromString(value, "value"),
                KIND_STRING);
    }

    public void set(String name, Blob value) {
        set(name, value, null, false);
    }

    public void set(String name, Blob value, String filename) {
        set(name, value, filename, true);
    }

    /** Compiler-facing ordered iteration ABI. */
    public String getName(int index) {
        assertAccess();
        checkIndex(index);
        return names[index];
    }

    /** Compiler-facing ordered iteration ABI. */
    public Object getValue(int index) {
        assertAccess();
        checkIndex(index);
        return values[index];
    }

    public boolean isFile(int index) {
        assertAccess();
        checkIndex(index);
        return kinds[index] == KIND_FILE;
    }

    public String getStringValue(int index) {
        assertAccess();
        checkIndex(index);
        if (kinds[index] != KIND_STRING) {
            throw new IllegalStateException("FormData entry is not a string");
        }
        return (String) values[index];
    }

    public File getFileValue(int index) {
        assertAccess();
        checkIndex(index);
        if (kinds[index] != KIND_FILE) {
            throw new IllegalStateException("FormData entry is not a File");
        }
        return (File) values[index];
    }

    /** Direct parser sink; FormUrlEncodedParser supplies already-decoded scalar strings. */
    @Override
    public void acceptFormEntry(String name, String value) {
        assertAccess();
        appendEntry(
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(value, "value"),
                KIND_STRING);
    }

    @Override
    public BufferedBodySnapshot snapshot() {
        assertAccess();
        String boundary = Objects.requireNonNull(
                boundarySource.nextBoundary(),
                "MultipartBoundarySource.nextBoundary returned null");
        return MultipartEncoder.encode(this, boundary);
    }

    String nameAt(int index) {
        return names[index];
    }

    Object valueAt(int index) {
        return values[index];
    }

    byte kindAt(int index) {
        return kinds[index];
    }

    static byte stringKind() {
        return KIND_STRING;
    }

    void appendParsedString(String name, String value) {
        appendEntry(name, value, KIND_STRING);
    }

    void appendParsedFile(String name, File value) {
        appendEntry(name, value, KIND_FILE);
    }

    private void append(String name, Blob value, String filename, boolean filenameGiven) {
        assertAccess();
        appendEntry(
                BodyScalar.fromString(name, "name"),
                fileValue(value, filename, filenameGiven),
                KIND_FILE);
    }

    private void set(String name, Blob value, String filename, boolean filenameGiven) {
        assertAccess();
        replaceEntries(
                BodyScalar.fromString(name, "name"),
                fileValue(value, filename, filenameGiven),
                KIND_FILE);
    }

    private File fileValue(Blob value, String filename, boolean filenameGiven) {
        Blob checked = Objects.requireNonNull(value, "value");
        checked.assertAccess();
        if (checked.getRuntime() != runtime) {
            throw new IllegalArgumentException("FormData Blob belongs to another RuntimeInstance");
        }
        if (!filenameGiven && checked instanceof File) return (File) checked;
        String name = filenameGiven
                ? BodyScalar.fromString(filename, "filename")
                : "blob";
        return File.fromBlob(checked, name);
    }

    private void replaceEntries(String name, Object value, byte kind) {
        int first = -1;
        int write = 0;
        for (int read = 0; read < size; read++) {
            if (names[read].equals(name)) {
                if (first < 0) {
                    first = write;
                    names[write] = name;
                    values[write] = value;
                    kinds[write] = kind;
                    write++;
                }
                continue;
            }
            if (write != read) {
                names[write] = names[read];
                values[write] = values[read];
                kinds[write] = kinds[read];
            }
            write++;
        }
        clearRange(write, size);
        size = write;
        if (first < 0) appendEntry(name, value, kind);
    }

    private void appendEntry(String name, Object value, byte kind) {
        ensureCapacity(size + 1);
        names[size] = name;
        values[size] = value;
        kinds[size] = kind;
        size++;
    }

    private void ensureCapacity(int required) {
        if (required <= names.length) return;
        int capacity = Math.max(required, names.length + (names.length >> 1) + 1);
        names = Arrays.copyOf(names, capacity);
        values = Arrays.copyOf(values, capacity);
        kinds = Arrays.copyOf(kinds, capacity);
    }

    private void clearRange(int start, int end) {
        for (int index = start; index < end; index++) {
            names[index] = null;
            values[index] = null;
            kinds[index] = 0;
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private void assertAccess() {
        BodyRuntimeChecks.assertLanguageExecution(runtime);
    }
}
