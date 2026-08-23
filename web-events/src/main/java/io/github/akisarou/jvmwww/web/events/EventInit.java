package io.github.akisarou.jvmwww.web.events;

/** Constructor options for {@link Event}. */
public class EventInit {
    public static final EventInit DEFAULT = new EventInit(false, false, false);

    private final boolean bubbles;
    private final boolean cancelable;
    private final boolean composed;

    public EventInit() {
        this(false, false, false);
    }

    public EventInit(boolean bubbles, boolean cancelable, boolean composed) {
        this.bubbles = bubbles;
        this.cancelable = cancelable;
        this.composed = composed;
    }

    public final boolean isBubbles() {
        return bubbles;
    }

    public final boolean isCancelable() {
        return cancelable;
    }

    public final boolean isComposed() {
        return composed;
    }
}
