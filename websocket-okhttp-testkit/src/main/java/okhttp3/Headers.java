package okhttp3;

public final class Headers {
    private final String[] names;
    private final String[] values;

    public Headers(String[] names, String[] values) {
        this.names = names.clone();
        this.values = values.clone();
    }

    public int size() { return names.length; }
    public String name(int index) { return names[index]; }
    public String value(int index) { return values[index]; }
}
