package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

/** Host boundary for uncaught idle-callback exceptions. */
@FunctionalInterface
public interface IdleCallbackExceptionReporter {
    void report(RuntimeInstance runtime, Throwable error);
}
