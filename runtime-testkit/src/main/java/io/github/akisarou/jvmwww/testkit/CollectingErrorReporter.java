package io.github.akisarou.jvmwww.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeErrorPhase;
import io.github.akisarou.jvmwww.runtime.RuntimeErrorReporter;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Error reporter that makes uncaught scheduler failures observable in tests. */
public final class CollectingErrorReporter implements RuntimeErrorReporter {
    public static final class Entry {
        private final RuntimeErrorPhase phase;
        private final Throwable error;

        Entry(RuntimeErrorPhase phase, Throwable error) {
            this.phase = phase;
            this.error = error;
        }

        public RuntimeErrorPhase getPhase() {
            return phase;
        }

        public Throwable getError() {
            return error;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    @Override
    public synchronized void report(
            RuntimeInstance runtime,
            RuntimeErrorPhase phase,
            Throwable error) {
        entries.add(new Entry(phase, error));
    }

    public synchronized List<Entry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }
}
