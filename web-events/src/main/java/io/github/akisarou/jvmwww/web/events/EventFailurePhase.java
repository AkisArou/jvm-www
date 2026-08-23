package io.github.akisarou.jvmwww.web.events;

/** Identifies a Web-events callback boundary that reported a non-fatal failure. */
public enum EventFailurePhase {
    EVENT_LISTENER,
    ABORT_ALGORITHM
}
