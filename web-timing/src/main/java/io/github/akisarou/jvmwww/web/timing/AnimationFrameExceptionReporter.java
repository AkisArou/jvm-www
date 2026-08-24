package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

/** Host hook for exceptions thrown by animation-frame callbacks. */
@FunctionalInterface
public interface AnimationFrameExceptionReporter {
    void report(RuntimeInstance runtime, Throwable error);
}
