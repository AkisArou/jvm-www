package io.github.akisarou.jvmwww.web.events;

/** Listener registration options. */
public final class AddEventListenerOptions extends EventListenerOptions {
    public static final AddEventListenerOptions DEFAULT =
            new AddEventListenerOptions(false, false, false, null);

    private final boolean passive;
    private final boolean once;
    private final AbortSignal signal;

    public AddEventListenerOptions() {
        this(false, false, false, null);
    }

    public AddEventListenerOptions(boolean capture) {
        this(capture, false, false, null);
    }

    public AddEventListenerOptions(
            boolean capture,
            boolean passive,
            boolean once,
            AbortSignal signal) {
        super(capture);
        this.passive = passive;
        this.once = once;
        this.signal = signal;
    }

    public boolean isPassive() {
        return passive;
    }

    public boolean isOnce() {
        return once;
    }

    public AbortSignal getSignal() {
        return signal;
    }
}
