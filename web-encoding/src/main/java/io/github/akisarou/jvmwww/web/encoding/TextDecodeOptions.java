package io.github.akisarou.jvmwww.web.encoding;

/** Per-call options for {@link TextDecoder#decode(byte[], TextDecodeOptions)}. */
public final class TextDecodeOptions {
    public static final TextDecodeOptions DEFAULT = new TextDecodeOptions(false);
    public static final TextDecodeOptions STREAM = new TextDecodeOptions(true);

    private final boolean stream;

    public TextDecodeOptions() {
        this(false);
    }

    public TextDecodeOptions(boolean stream) {
        this.stream = stream;
    }

    public boolean isStream() {
        return stream;
    }
}
