package io.github.akisarou.jvmwww.web.timing.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.timing.IdleCallbackExceptionReporter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test-only idle callback exception collector. */
public final class CollectingIdleCallbackExceptionReporter
        implements IdleCallbackExceptionReporter {
    private final ArrayList<Throwable> errors = new ArrayList<Throwable>();

    @Override
    public void report(RuntimeInstance runtime, Throwable error) {
        errors.add(error);
    }

    public List<Throwable> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
