package okhttp3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic test-only ordered multi-map matching the adapter's used API. */
public final class Headers {
    private final String[] names;
    private final String[] values;

    private Headers(List<String> names, List<String> values) {
        this.names = names.toArray(new String[names.size()]);
        this.values = values.toArray(new String[values.size()]);
    }

    public int size() {
        return names.length;
    }

    public String name(int index) {
        return names[index];
    }

    public String value(int index) {
        return values[index];
    }

    public String get(String name) {
        String result = null;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                result = values[i];
            }
        }
        return result;
    }

    public static final class Builder {
        private final ArrayList<String> names = new ArrayList<String>();
        private final ArrayList<String> values = new ArrayList<String>();

        public Builder add(String name, String value) {
            names.add(Objects.requireNonNull(name, "name"));
            values.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Headers build() {
            return new Headers(names, values);
        }
    }
}
