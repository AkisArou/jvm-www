package io.github.akisarou.jvmwww.web.events;

import java.lang.ref.WeakReference;

/** Ordered source-to-dependent edge with conditional strong retention. */
final class AbortSignalDependency extends WeakReference<AbortSignal> {
    private AbortSignal strongSignal;

    AbortSignalDependency(AbortSignal signal, boolean retainStrongly) {
        super(signal);
        if (retainStrongly) {
            strongSignal = signal;
        }
    }

    AbortSignal getSignal() {
        return strongSignal != null ? strongSignal : get();
    }

    void setRetainedStrongly(boolean retainStrongly) {
        if (retainStrongly) {
            strongSignal = get();
        } else {
            strongSignal = null;
        }
    }
}
