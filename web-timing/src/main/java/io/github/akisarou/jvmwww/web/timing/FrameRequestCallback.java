package io.github.akisarou.jvmwww.web.timing;

/** Browser-shaped requestAnimationFrame callback. */
@FunctionalInterface
public interface FrameRequestCallback {
    /** Receives a shared frame timestamp in milliseconds relative to the timing origin. */
    void run(double timestampMilliseconds) throws Throwable;
}
