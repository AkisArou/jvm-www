package io.github.akisarou.jvmwww.web.timing;

/** Browser-shaped callback used by {@link IdleCallbackScheduler}. */
@FunctionalInterface
public interface IdleRequestCallback {
    void run(IdleDeadline deadline) throws Throwable;
}
