package io.github.akisarou.jvmwww.web.timing.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.timing.AnimationFrameExceptionReporter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records callback exceptions without interrupting the remaining frame callbacks. */
public final class CollectingAnimationFrameExceptionReporter
        implements AnimationFrameExceptionReporter {
    private final List<Throwable> errors = new ArrayList<Throwable>();

    @Override
    public void report(RuntimeInstance runtime, Throwable error) {
        errors.add(error);
    }

    public List<Throwable> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
