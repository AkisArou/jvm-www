package io.github.akisarou.jvmwww.web.console;

/** Synchronous host printer for one owner-confined Console object. */
public interface ConsoleSink {
    /**
     * Prints one processed argument list at the supplied group depth.
     *
     * <p>The array is valid only for this call. A sink must not mutate it and must copy any values
     * it needs to retain.</p>
     */
    void print(ConsoleLogLevel level, int groupDepth, Object[] arguments);

    /** Clears host presentation state when supported. */
    void clear();

    /** Always-available sink for profiles with no visible developer console. */
    ConsoleSink DISCARD = new ConsoleSink() {
        @Override
        public void print(ConsoleLogLevel level, int groupDepth, Object[] arguments) {}

        @Override
        public void clear() {}
    };
}
