package io.github.akisarou.jvmwww.web.console;

/** Monotonic clock used by Console timing state. */
public interface ConsoleClock {
    long nowNanos();

    ConsoleClock SYSTEM = new ConsoleClock() {
        @Override
        public long nowNanos() {
            return System.nanoTime();
        }
    };
}
