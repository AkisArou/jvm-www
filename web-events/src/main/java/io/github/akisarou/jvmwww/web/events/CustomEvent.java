package io.github.akisarou.jvmwww.web.events;

/** Event carrying an application-defined detail value. */
public final class CustomEvent<T> extends Event {
    private T detail;

    public CustomEvent(String type) {
        this(type, new CustomEventInit<T>());
    }

    public CustomEvent(String type, T detail) {
        this(type, new CustomEventInit<T>(detail));
    }

    public CustomEvent(String type, CustomEventInit<T> init) {
        super(type, init == null ? EventInit.DEFAULT : init);
        detail = init == null ? null : init.getDetail();
    }

    public T getDetail() {
        return detail;
    }

    /** Legacy reinitialization. It is a no-op while this event is being dispatched. */
    public void initCustomEvent(
            String type,
            boolean bubbles,
            boolean cancelable,
            T detail) {
        if (isDispatching()) {
            return;
        }
        initEvent(type, bubbles, cancelable);
        this.detail = detail;
    }
}
