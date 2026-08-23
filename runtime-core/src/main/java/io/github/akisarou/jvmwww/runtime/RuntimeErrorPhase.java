package io.github.akisarou.jvmwww.runtime;

/** Identifies where an uncaught runtime failure originated. */
public enum RuntimeErrorPhase {
    HOST_TASK,
    MICROTASK,
    DISCARD
}
