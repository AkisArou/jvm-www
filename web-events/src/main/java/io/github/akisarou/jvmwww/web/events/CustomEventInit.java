package io.github.akisarou.jvmwww.web.events;

/** Constructor options for {@link CustomEvent}. */
public final class CustomEventInit<T> extends EventInit {
    private final T detail;

    public CustomEventInit() {
        this(false, false, false, null);
    }

    public CustomEventInit(T detail) {
        this(false, false, false, detail);
    }

    public CustomEventInit(boolean bubbles, boolean cancelable, boolean composed, T detail) {
        super(bubbles, cancelable, composed);
        this.detail = detail;
    }

    public T getDetail() {
        return detail;
    }
}
