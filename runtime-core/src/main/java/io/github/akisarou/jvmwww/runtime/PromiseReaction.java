package io.github.akisarou.jvmwww.runtime;

/**
 * Compiler-facing body of one Promise reaction.
 *
 * <p>The source is settled before this method runs. Generated implementations read the source's
 * specialized payload and resolve the destination with the handler's result. When the method
 * returns without resolving the destination, the runtime fulfills it with {@code undefined}
 * (represented by the void payload). Throwing {@link JsThrownValue} rejects with its exact
 * JavaScript value; another non-fatal Java failure rejects with that failure as a reference.</p>
 */
@FunctionalInterface
public interface PromiseReaction {
    /** Runs as a microtask on the source and destination runtime's owner thread. */
    void execute(RuntimeInstance runtime, JsPromise source, JsPromise destination) throws Throwable;
}
