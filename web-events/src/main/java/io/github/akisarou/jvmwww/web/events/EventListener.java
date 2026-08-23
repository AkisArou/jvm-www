package io.github.akisarou.jvmwww.web.events;

/** Java ABI for one Web-compatible event listener callback. */
@FunctionalInterface
public interface EventListener {
    void handleEvent(Event event) throws Throwable;
}
