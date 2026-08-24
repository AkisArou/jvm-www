package io.github.akisarou.jvmwww.web.websocket;

import io.github.akisarou.jvmwww.web.events.EventInit;

/** Constructor options for CloseEvent. */
public final class CloseEventInit extends EventInit {
    public static final CloseEventInit DEFAULT = new CloseEventInit(false, 0, "", false, false, false);
    private final boolean wasClean;
    private final int code;
    private final String reason;

    public CloseEventInit() { this(false, 0, "", false, false, false); }
    public CloseEventInit(boolean wasClean, int code, String reason) {
        this(wasClean, code, reason, false, false, false);
    }
    public CloseEventInit(boolean wasClean, int code, String reason, boolean bubbles, boolean cancelable, boolean composed) {
        super(bubbles, cancelable, composed);
        if (code < 0 || code > 0xffff) throw new IllegalArgumentException("CloseEvent code must fit an unsigned short");
        this.wasClean = wasClean;
        this.code = code;
        this.reason = WebSocketScalar.fromString(reason == null ? "" : reason, "reason");
    }
    public boolean isWasClean() { return wasClean; }
    public int getCode() { return code; }
    public String getReason() { return reason; }
}
