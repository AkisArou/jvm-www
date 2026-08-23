package io.github.akisarou.jvmwww.web.encoding;

/** Constructor options for {@link TextDecoder}. */
public final class TextDecoderOptions {
    public static final TextDecoderOptions DEFAULT = new TextDecoderOptions(false, false);

    private final boolean fatal;
    private final boolean ignoreBOM;

    public TextDecoderOptions() {
        this(false, false);
    }

    public TextDecoderOptions(boolean fatal, boolean ignoreBOM) {
        this.fatal = fatal;
        this.ignoreBOM = ignoreBOM;
    }

    public boolean isFatal() {
        return fatal;
    }

    public boolean isIgnoreBOM() {
        return ignoreBOM;
    }
}
