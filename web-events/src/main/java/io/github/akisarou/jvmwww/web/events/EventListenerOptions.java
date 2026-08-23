package io.github.akisarou.jvmwww.web.events;

/** Listener matching options used by {@code removeEventListener}. */
public class EventListenerOptions {
    public static final EventListenerOptions DEFAULT = new EventListenerOptions(false);

    private final boolean capture;

    public EventListenerOptions() {
        this(false);
    }

    public EventListenerOptions(boolean capture) {
        this.capture = capture;
    }

    public final boolean isCapture() {
        return capture;
    }
}
