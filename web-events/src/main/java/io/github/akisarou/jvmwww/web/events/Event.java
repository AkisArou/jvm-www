package io.github.akisarou.jvmwww.web.events;

import java.util.Objects;

/** Web-compatible synthetic event for owner-confined capability objects. */
public class Event {
    public static final short NONE = 0;
    public static final short CAPTURING_PHASE = 1;
    public static final short AT_TARGET = 2;
    public static final short BUBBLING_PHASE = 3;

    private String type;
    private EventTarget target;
    private EventTarget currentTarget;
    private short eventPhase = NONE;
    private final double timeStamp;
    private boolean bubbles;
    private boolean cancelable;
    private final boolean composed;
    private boolean initialized;
    private boolean dispatching;
    private boolean stopPropagation;
    private boolean stopImmediatePropagation;
    private boolean canceled;
    private boolean inPassiveListener;

    public Event(String type) {
        this(type, EventInit.DEFAULT);
    }

    public Event(String type, EventInit init) {
        EventInit checked = init == null ? EventInit.DEFAULT : init;
        this.type = Objects.requireNonNull(type, "type");
        this.bubbles = checked.isBubbles();
        this.cancelable = checked.isCancelable();
        this.composed = checked.isComposed();
        this.timeStamp = System.nanoTime() / 1_000_000.0;
        this.initialized = true;
    }

    public final String getType() {
        return type;
    }

    public final EventTarget getTarget() {
        return target;
    }

    /** Legacy alias for {@link #getTarget()}. */
    public final EventTarget getSrcElement() {
        return target;
    }

    public final EventTarget getCurrentTarget() {
        return currentTarget;
    }

    /**
     * Returns this profile's dispatch path.
     *
     * <p>Author-created EventTargets do not participate in a tree, so the path contains only the
     * target while dispatch is active and is empty otherwise.</p>
     */
    public final EventTarget[] composedPath() {
        if (!dispatching || target == null) {
            return new EventTarget[0];
        }
        return new EventTarget[] {target};
    }

    public final short getEventPhase() {
        return eventPhase;
    }

    public final double getTimeStamp() {
        return timeStamp;
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

    /** Synthetic events created by application code are never trusted user-agent events. */
    public final boolean isTrusted() {
        return false;
    }

    public final void stopPropagation() {
        stopPropagation = true;
    }

    public final void stopImmediatePropagation() {
        stopPropagation = true;
        stopImmediatePropagation = true;
    }

    public final boolean isCancelBubble() {
        return stopPropagation;
    }

    public final boolean getCancelBubble() {
        return stopPropagation;
    }

    /** The legacy setter only sets the flag; assigning false does not clear it. */
    public final void setCancelBubble(boolean value) {
        if (value) {
            stopPropagation = true;
        }
    }

    public final void preventDefault() {
        if (cancelable && !inPassiveListener) {
            canceled = true;
        }
    }

    public final boolean isDefaultPrevented() {
        return canceled;
    }

    /** Legacy returnValue is false exactly when the event has been canceled. */
    public final boolean isReturnValue() {
        return !canceled;
    }

    public final boolean getReturnValue() {
        return !canceled;
    }

    /** Assigning false invokes preventDefault; assigning true is a no-op. */
    public final void setReturnValue(boolean value) {
        if (!value) {
            preventDefault();
        }
    }

    /** Legacy reinitialization. It is a no-op while this event is being dispatched. */
    public void initEvent(String type, boolean bubbles, boolean cancelable) {
        if (dispatching) {
            return;
        }
        this.type = Objects.requireNonNull(type, "type");
        this.target = null;
        this.currentTarget = null;
        this.eventPhase = NONE;
        this.bubbles = bubbles;
        this.cancelable = cancelable;
        this.stopPropagation = false;
        this.stopImmediatePropagation = false;
        this.canceled = false;
        this.inPassiveListener = false;
        this.initialized = true;
    }

    final boolean isInitializedForDispatch() {
        return initialized;
    }

    final boolean isDispatching() {
        return dispatching;
    }

    final boolean isPropagationStopped() {
        return stopPropagation;
    }

    final boolean isImmediatePropagationStopped() {
        return stopImmediatePropagation;
    }

    final void beginDispatch(EventTarget dispatchTarget) {
        if (dispatching || !initialized) {
            throw DOMException.invalidState(
                    "The Event is already being dispatched or was not initialized");
        }
        target = Objects.requireNonNull(dispatchTarget, "dispatchTarget");
        currentTarget = dispatchTarget;
        eventPhase = AT_TARGET;
        dispatching = true;
    }

    final void setPassiveListener(boolean passive) {
        inPassiveListener = passive;
    }

    final void finishDispatch() {
        eventPhase = NONE;
        currentTarget = null;
        dispatching = false;
        stopPropagation = false;
        stopImmediatePropagation = false;
        inPassiveListener = false;
    }
}
