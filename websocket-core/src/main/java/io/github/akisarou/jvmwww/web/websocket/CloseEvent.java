package io.github.akisarou.jvmwww.web.websocket;

import java.util.Objects;

/** Close event carrying the peer-visible close result. */
public final class CloseEvent extends QueuedWebSocketEvent {
    private final boolean wasClean;
    private final int code;
    private final String reason;
    private final boolean fireErrorFirst;

    public CloseEvent(String type) { this(type, CloseEventInit.DEFAULT); }
    public CloseEvent(String type, CloseEventInit init) {
        this(type, init == null ? CloseEventInit.DEFAULT : init, false);
    }
    private CloseEvent(String type, CloseEventInit init, boolean fireErrorFirst) {
        super(Objects.requireNonNull(type, "type"), init);
        this.wasClean = init.isWasClean();
        this.code = init.getCode();
        this.reason = init.getReason();
        this.fireErrorFirst = fireErrorFirst;
    }
    private CloseEvent(String type, boolean wasClean, int code, String reason, boolean fireErrorFirst) {
        super(Objects.requireNonNull(type, "type"));
        this.wasClean = wasClean;
        this.code = code;
        this.reason = WebSocketScalar.fromString(reason == null ? "" : reason, "reason");
        this.fireErrorFirst = fireErrorFirst;
    }
    static CloseEvent transportClose(int code, String reason, boolean clean) {
        return new CloseEvent("close", clean, normalizeCode(code, clean), reason, false);
    }
    static CloseEvent failure() { return new CloseEvent("close", false, 1006, "", true); }
    public boolean isWasClean() { return wasClean; }
    public int getCode() { return code; }
    public String getReason() { return reason; }
    @Override void deliver(WebSocket socket) { socket.deliverClose(this, fireErrorFirst); }
    private static int normalizeCode(int code, boolean clean) {
        if (code == 0) return clean ? 1005 : 1006;
        if (code < 0 || code > 0xffff) return 1006;
        return code;
    }
}
