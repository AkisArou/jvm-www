package io.github.akisarou.jvmwww.web.websocket;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.web.bodies.Blob;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.encoding.Utf8Codec;
import io.github.akisarou.jvmwww.web.events.DOMException;
import io.github.akisarou.jvmwww.web.events.Event;
import io.github.akisarou.jvmwww.web.events.EventListener;
import io.github.akisarou.jvmwww.web.events.EventTarget;
import io.github.akisarou.jvmwww.web.url.URL;
import java.util.Objects;

/** Owner-confined browser-shaped WebSocket state machine over a replaceable transport. */
public final class WebSocket extends EventTarget
        implements WebSocketTransportListener,
                EventListener,
                RuntimeTask,
                RuntimeOwnedResource {
    public static final int CONNECTING = 0;
    public static final int OPEN = 1;
    public static final int CLOSING = 2;
    public static final int CLOSED = 3;
    public static final String BINARY_TYPE_BLOB = "blob";
    public static final String BINARY_TYPE_ARRAYBUFFER = "arraybuffer";

    private static final String OPEN_EVENT_TYPE = "open";
    private static final String MESSAGE_EVENT_TYPE = "message";
    private static final String ERROR_EVENT_TYPE = "error";
    private static final String CLOSE_EVENT_TYPE = "close";
    private static final String[] NO_PROTOCOLS = new String[0];
    private static final int NO_RUNTIME_RESOURCE_SLOT = -1;

    private final String url;
    private final String origin;
    private final String[] offeredProtocols;
    private QueuedWebSocketEvent eventHead;
    private QueuedWebSocketEvent eventTail;
    private BufferSampleEvent bufferSampleEvent;
    private boolean taskActive;
    private boolean openQueued;
    private boolean closingQueued;
    private boolean terminalQueued;
    private boolean bufferSampleQueued;
    private boolean discarded;
    private boolean transportTerminalOrCancelled;
    private WebSocketTransportCall call;
    private int runtimeResourceSlot;

    private int readyState = CONNECTING;
    private String binaryType = BINARY_TYPE_BLOB;
    private String protocol = "";
    private String extensions = "";
    private long bufferedAmount;
    private long nonTransportBufferedAmount;
    private EventListener onOpen;
    private EventListener onMessage;
    private EventListener onError;
    private EventListener onClose;

    public WebSocket(RuntimeInstance runtime, WebSocketTransport transport, String url) {
        this(runtime, transport, url, (String[]) null);
    }

    public WebSocket(
            RuntimeInstance runtime,
            WebSocketTransport transport,
            String url,
            String protocol) {
        this(runtime, transport, url, new String[] {Objects.requireNonNull(protocol, "protocol")});
    }

    public WebSocket(
            RuntimeInstance runtime,
            WebSocketTransport transport,
            String input,
            String[] protocols) {
        super(checkedRuntime(runtime));
        WebSocketUrl parsed = parseUrl(runtime, Objects.requireNonNull(input, "url"));
        this.url = parsed.url;
        this.origin = parsed.origin;
        this.offeredProtocols = validateProtocols(protocols);
        runtimeResourceSlot = runtime.registerOwnedResource(this);
        start(Objects.requireNonNull(transport, "transport"));
    }

    public WebSocket(RuntimeInstance runtime, WebSocketTransport transport, URL input) {
        this(runtime, transport, input, null);
    }

    public WebSocket(
            RuntimeInstance runtime,
            WebSocketTransport transport,
            URL input,
            String[] protocols) {
        super(checkedRuntime(runtime));
        URL checked = Objects.requireNonNull(input, "url");
        if (checked.getRuntime() != runtime) {
            throw new IllegalArgumentException("WebSocket URL belongs to another RuntimeInstance");
        }
        WebSocketUrl parsed = parseCanonicalUrl(checked);
        this.url = parsed.url;
        this.origin = parsed.origin;
        this.offeredProtocols = validateProtocols(protocols);
        runtimeResourceSlot = runtime.registerOwnedResource(this);
        start(Objects.requireNonNull(transport, "transport"));
    }

    public String getUrl() {
        assertAccess();
        return url;
    }

    public int getReadyState() {
        assertAccess();
        return readyState;
    }

    public long getBufferedAmount() {
        assertAccess();
        if (readyState != CLOSED && !isBufferSampleQueued()) {
            refreshBufferedAmountFromTransport(true);
        }
        return bufferedAmount;
    }

    public String getExtensions() {
        assertAccess();
        return extensions;
    }

    public String getProtocol() {
        assertAccess();
        return protocol;
    }

    public String getBinaryType() {
        assertAccess();
        return binaryType;
    }

    public void setBinaryType(String value) {
        assertAccess();
        String checked = Objects.requireNonNull(value, "value");
        if (!BINARY_TYPE_BLOB.equals(checked) && !BINARY_TYPE_ARRAYBUFFER.equals(checked)) {
            throw new JsTypeError("WebSocket binaryType must be 'blob' or 'arraybuffer'");
        }
        binaryType = checked;
    }

    public EventListener getOnOpen() {
        assertAccess();
        return onOpen;
    }

    public EventListener getOnMessage() {
        assertAccess();
        return onMessage;
    }

    public EventListener getOnError() {
        assertAccess();
        return onError;
    }

    public EventListener getOnClose() {
        assertAccess();
        return onClose;
    }

    public void setOnOpen(EventListener listener) {
        assertAccess();
        onOpen = setHandler(OPEN_EVENT_TYPE, onOpen, listener);
    }

    public void setOnMessage(EventListener listener) {
        assertAccess();
        onMessage = setHandler(MESSAGE_EVENT_TYPE, onMessage, listener);
    }

    public void setOnError(EventListener listener) {
        assertAccess();
        onError = setHandler(ERROR_EVENT_TYPE, onError, listener);
    }

    public void setOnClose(EventListener listener) {
        assertAccess();
        onClose = setHandler(CLOSE_EVENT_TYPE, onClose, listener);
    }

    @Override
    public void handleEvent(Event event) throws Throwable {
        EventListener callback;
        String type = event.getType();
        if (OPEN_EVENT_TYPE.equals(type)) callback = onOpen;
        else if (MESSAGE_EVENT_TYPE.equals(type)) callback = onMessage;
        else if (ERROR_EVENT_TYPE.equals(type)) callback = onError;
        else if (CLOSE_EVENT_TYPE.equals(type)) callback = onClose;
        else callback = null;
        if (callback != null) callback.handleEvent(event);
    }

    public void send(String data) {
        assertAccess();
        if (readyState == CONNECTING) throw DOMException.invalidState("WebSocket is connecting");
        String scalar = WebSocketScalar.fromString(data, "data");
        long size = Utf8Codec.encodedLength(scalar);
        prepareBufferedAmountBaseline();
        addBufferedAmount(size);
        if (!canSendToTransport()) {
            addNonTransportBufferedAmount(size);
            return;
        }
        WebSocketTransportCall local = requireCall();
        boolean accepted = false;
        try {
            accepted = local.sendText(scalar);
        } catch (Throwable error) {
            rethrowIfFatal(error);
        }
        finishSend(accepted, size);
    }

    public void send(byte[] data) {
        send(data, 0, Objects.requireNonNull(data, "data").length);
    }

    public void send(byte[] data, int offset, int length) {
        assertAccess();
        byte[] checked = Objects.requireNonNull(data, "data");
        checkRange(checked.length, offset, length);
        if (readyState == CONNECTING) throw DOMException.invalidState("WebSocket is connecting");
        prepareBufferedAmountBaseline();
        if (!canSendToTransport()) {
            addBufferedAmount(length);
            addNonTransportBufferedAmount(length);
            return;
        }
        byte[] owned = new byte[length];
        System.arraycopy(checked, offset, owned, 0, length);
        BufferedBodySnapshot snapshot = BufferedBodySnapshot.fromOwnedBytes(owned, null);
        addBufferedAmount(length);
        sendBinarySnapshot(snapshot, length);
    }

    public void send(Blob data) {
        assertAccess();
        Blob checked = Objects.requireNonNull(data, "data");
        if (checked.getRuntime() != getRuntime()) {
            throw new IllegalArgumentException("WebSocket Blob belongs to another RuntimeInstance");
        }
        if (readyState == CONNECTING) throw DOMException.invalidState("WebSocket is connecting");
        long size = checked.getSize();
        prepareBufferedAmountBaseline();
        if (!canSendToTransport()) {
            addBufferedAmount(size);
            addNonTransportBufferedAmount(size);
            return;
        }
        BufferedBodySnapshot snapshot = checked.snapshot();
        addBufferedAmount(size);
        sendBinarySnapshot(snapshot, size);
    }

    public void close() {
        closeInternal(0, "", false);
    }

    public void close(int code) {
        closeInternal(code, "", true);
    }

    public void close(int code, String reason) {
        closeInternal(code, reason, true);
    }

    @Override
    public void onOpen(String selectedProtocol, String selectedExtensions) {
        String checkedProtocol = selectedProtocol == null ? "" : selectedProtocol;
        String checkedExtensions = selectedExtensions == null ? "" : selectedExtensions;
        if (!checkedProtocol.isEmpty() && !wasProtocolOffered(checkedProtocol)) {
            boolean cancelled = cancelCurrentCall();
            enqueueTerminalEvent(CloseEvent.failure(), cancelled);
            return;
        }
        enqueueOpen(new OpenEvent(checkedProtocol, checkedExtensions));
    }

    @Override
    public void onTextMessage(String text) {
        if (!canPublishNonTerminal()) return;
        enqueueEvent(MessageEvent.text(Objects.requireNonNull(text, "text"), origin), false);
    }

    @Override
    public void onBinaryMessage(byte[] bytes) {
        byte[] owned = Objects.requireNonNull(bytes, "bytes");
        if (!canPublishNonTerminal()) return;
        enqueueEvent(MessageEvent.binary(owned, origin), false);
    }

    @Override
    public void onClosing() {
        enqueueClosing();
    }

    @Override
    public void onClosed(int code, String reason, boolean wasClean) {
        enqueueTerminalEvent(CloseEvent.transportClose(code, reason, wasClean), true);
    }

    @Override
    public void onFailure(Throwable error) {
        Objects.requireNonNull(error, "error");
        enqueueTerminalEvent(CloseEvent.failure(), true);
    }

    @Override
    public void execute(RuntimeInstance runtime) {
        if (runtime != getRuntime()) {
            throw new IllegalArgumentException("WebSocket delivered by another RuntimeInstance");
        }
        QueuedWebSocketEvent event;
        synchronized (this) {
            if (discarded) return;
            event = eventHead;
            if (event == null) {
                taskActive = false;
                return;
            }
            eventHead = event.nextWebSocketEvent;
            if (eventHead == null) eventTail = null;
            event.nextWebSocketEvent = null;
        }
        try {
            event.deliver(this);
        } finally {
            boolean reschedule;
            synchronized (this) {
                if (discarded || eventHead == null) {
                    taskActive = false;
                    reschedule = false;
                } else {
                    reschedule = true;
                }
            }
            if (reschedule) getRuntime().admitHostTask(this);
        }
    }

    /** Runtime shutdown cancels the exact active transport even when no event is queued. */
    @Override
    public void closeForRuntime() {
        discard();
    }

    @Override
    public void discard() {
        releaseRuntimeOwnership();
        WebSocketTransportCall local;
        QueuedWebSocketEvent event;
        synchronized (this) {
            if (discarded) return;
            discarded = true;
            local = transportTerminalOrCancelled ? null : call;
            call = null;
            transportTerminalOrCancelled = true;
            event = eventHead;
            eventHead = null;
            eventTail = null;
            taskActive = false;
            bufferSampleQueued = false;
        }
        while (event != null) {
            QueuedWebSocketEvent next = event.nextWebSocketEvent;
            event.nextWebSocketEvent = null;
            event.discardPayload();
            event = next;
        }
        cancelQuietly(local);
    }

    String getBinaryTypeInternal() {
        return binaryType;
    }

    void deliverMessage(MessageEvent event) {
        if (readyState == OPEN) dispatchEvent(event);
    }

    void deliverClose(CloseEvent event, boolean fireErrorFirst) {
        if (readyState == CLOSED) return;
        if (event.isWasClean() && !fireErrorFirst) refreshBufferedAmountFromTransport(false);
        readyState = CLOSED;
        synchronized (this) {
            closingQueued = true;
            terminalQueued = true;
            transportTerminalOrCancelled = true;
            call = null;
        }
        if (fireErrorFirst) dispatchEvent(new Event(ERROR_EVENT_TYPE));
        dispatchEvent(event);
    }

    private void start(WebSocketTransport transport) {
        try {
            WebSocketTransportCall started = Objects.requireNonNull(
                    transport.start(new WebSocketTransportRequest(url, offeredProtocols), this),
                    "WebSocketTransport.start returned null");
            boolean cancel;
            synchronized (this) {
                call = started;
                cancel = discarded || (terminalQueued && !transportTerminalOrCancelled);
                if (cancel) transportTerminalOrCancelled = true;
            }
            if (cancel) cancelQuietly(started);
        } catch (Throwable error) {
            if (isFatal(error)) {
                cleanupAfterFatalStart(error);
                rethrowIfFatal(error);
            }
            onFailure(error);
        }
    }

    private void sendBinarySnapshot(BufferedBodySnapshot snapshot, long size) {
        WebSocketTransportCall local = requireCall();
        boolean accepted = false;
        try {
            accepted = local.sendBinary(snapshot);
        } catch (Throwable error) {
            rethrowIfFatal(error);
        }
        finishSend(accepted, size);
    }

    private void finishSend(boolean accepted, long size) {
        if (accepted) {
            scheduleBufferSample();
            return;
        }
        addNonTransportBufferedAmount(size);
        failConnectionFromOwner();
    }

    private void failConnectionFromOwner() {
        if (readyState == CLOSED) return;
        readyState = CLOSING;
        boolean cancelled = cancelCurrentCall();
        enqueueTerminalEvent(CloseEvent.failure(), cancelled);
    }

    private void closeInternal(int code, String reason, boolean codePresent) {
        assertAccess();
        String checkedReason = codePresent
                ? WebSocketScalar.fromString(reason == null ? "" : reason, "reason")
                : "";
        if (codePresent && code != 1000 && (code < 3000 || code > 4999)) {
            throw new DOMException(
                    "WebSocket close code must be 1000 or between 3000 and 4999",
                    "InvalidAccessError");
        }
        if (Utf8Codec.encodedLength(checkedReason) > 123) {
            throw new DOMException(
                    "WebSocket close reason exceeds 123 UTF-8 bytes", "SyntaxError");
        }
        if (readyState == CLOSING || readyState == CLOSED) return;
        WebSocketTransportCall local;
        synchronized (this) {
            closingQueued = true;
            local = call;
        }
        if (readyState == CONNECTING) {
            readyState = CLOSING;
            boolean cancelled = cancelCurrentCall();
            enqueueTerminalEvent(CloseEvent.failure(), cancelled);
            return;
        }
        if (local == null) {
            readyState = CLOSING;
            enqueueTerminalEvent(CloseEvent.failure(), true);
            return;
        }
        readyState = CLOSING;
        boolean accepted = false;
        try {
            accepted = local.close(codePresent ? code : 0, checkedReason);
        } catch (Throwable error) {
            rethrowIfFatal(error);
        }
        if (!accepted) {
            boolean cancelled = cancelCurrentCall();
            enqueueTerminalEvent(CloseEvent.failure(), cancelled);
        }
    }

    private void deliverOpen(
            OpenEvent event,
            String selectedProtocol,
            String selectedExtensions) {
        if (readyState != CONNECTING) return;
        protocol = selectedProtocol;
        extensions = selectedExtensions;
        readyState = OPEN;
        dispatchEvent(event);
    }

    private void deliverClosing() {
        if (readyState == CONNECTING || readyState == OPEN) readyState = CLOSING;
    }

    private void deliverBufferSample() {
        synchronized (this) {
            bufferSampleQueued = false;
        }
        if (readyState != CLOSED) refreshBufferedAmountFromTransport(true);
    }

    private void prepareBufferedAmountBaseline() {
        if (readyState != CLOSED && !isBufferSampleQueued()) {
            refreshBufferedAmountFromTransport(true);
        }
    }

    private void refreshBufferedAmountFromTransport(boolean failOnError) {
        WebSocketTransportCall local;
        synchronized (this) {
            local = call;
        }
        if (local == null) return;
        try {
            long queued = local.getQueuedByteCount();
            if (queued < 0L) {
                throw new IllegalStateException(
                        "WebSocket transport returned a negative queued-byte count");
            }
            bufferedAmount = saturatingAdd(queued, nonTransportBufferedAmount);
        } catch (Throwable error) {
            rethrowIfFatal(error);
            if (failOnError) failConnectionFromOwner();
        }
    }

    private boolean canSendToTransport() {
        if (readyState != OPEN) return false;
        synchronized (this) {
            return !closingQueued && !terminalQueued && call != null;
        }
    }

    private boolean canPublishNonTerminal() {
        synchronized (this) {
            return !discarded && !closingQueued && !terminalQueued;
        }
    }

    private boolean isBufferSampleQueued() {
        synchronized (this) {
            return bufferSampleQueued;
        }
    }

    private void enqueueOpen(OpenEvent event) {
        boolean admit = false;
        synchronized (this) {
            if (discarded || closingQueued || terminalQueued || openQueued) return;
            openQueued = true;
            appendEventLocked(event);
            if (!taskActive) {
                taskActive = true;
                admit = true;
            }
        }
        if (admit) getRuntime().admitHostTask(this);
    }

    private void enqueueClosing() {
        boolean admit = false;
        synchronized (this) {
            if (discarded || closingQueued || terminalQueued) return;
            closingQueued = true;
            appendEventLocked(new ClosingEvent());
            if (!taskActive) {
                taskActive = true;
                admit = true;
            }
        }
        if (admit) getRuntime().admitHostTask(this);
    }

    private void scheduleBufferSample() {
        boolean admit = false;
        synchronized (this) {
            if (discarded || terminalQueued || bufferSampleQueued) return;
            bufferSampleQueued = true;
            if (bufferSampleEvent == null) bufferSampleEvent = new BufferSampleEvent();
            appendEventLocked(bufferSampleEvent);
            if (!taskActive) {
                taskActive = true;
                admit = true;
            }
        }
        if (admit) getRuntime().admitHostTask(this);
    }

    private void enqueueEvent(QueuedWebSocketEvent event, boolean terminal) {
        enqueueEvent(event, terminal, false);
    }

    private void enqueueTerminalEvent(
            QueuedWebSocketEvent event,
            boolean transportDone) {
        enqueueEvent(event, true, transportDone);
    }

    private void enqueueEvent(
            QueuedWebSocketEvent event,
            boolean terminal,
            boolean transportDone) {
        boolean admit = false;
        boolean releaseOwnership = false;
        synchronized (this) {
            if (discarded || terminalQueued) {
                event.discardPayload();
                return;
            }
            if (terminal) {
                terminalQueued = true;
                closingQueued = true;
                if (transportDone) transportTerminalOrCancelled = true;
                releaseOwnership = true;
            }
            appendEventLocked(event);
            if (!taskActive) {
                taskActive = true;
                admit = true;
            }
        }
        if (releaseOwnership) releaseRuntimeOwnership();
        if (admit) getRuntime().admitHostTask(this);
    }

    private void appendEventLocked(QueuedWebSocketEvent event) {
        if (eventTail == null) eventHead = event;
        else eventTail.nextWebSocketEvent = event;
        eventTail = event;
    }

    private EventListener setHandler(String type, EventListener previous, EventListener next) {
        if (previous == next) return previous;
        if (previous == null && next != null) addEventListener(type, this);
        if (previous != null && next == null) removeEventListener(type, this);
        return next;
    }

    private boolean wasProtocolOffered(String value) {
        for (String offered : offeredProtocols) {
            if (offered.equals(value)) return true;
        }
        return false;
    }

    private WebSocketTransportCall requireCall() {
        synchronized (this) {
            if (call == null) {
                throw new IllegalStateException("WebSocket transport call is unavailable");
            }
            return call;
        }
    }

    private boolean cancelCurrentCall() {
        WebSocketTransportCall local;
        synchronized (this) {
            local = call;
            if (local != null) transportTerminalOrCancelled = true;
        }
        cancelQuietly(local);
        return local != null;
    }

    private void releaseRuntimeOwnership() {
        final int slot;
        synchronized (this) {
            slot = runtimeResourceSlot;
            runtimeResourceSlot = NO_RUNTIME_RESOURCE_SLOT;
        }
        if (slot != NO_RUNTIME_RESOURCE_SLOT) {
            getRuntime().unregisterOwnedResource(this, slot);
        }
    }

    private void cleanupAfterFatalStart(Throwable original) {
        try {
            discard();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != original) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private void addBufferedAmount(long amount) {
        bufferedAmount = saturatingAdd(bufferedAmount, amount);
    }

    private void addNonTransportBufferedAmount(long amount) {
        nonTransportBufferedAmount = saturatingAdd(nonTransportBufferedAmount, amount);
    }

    private void assertAccess() {
        WebSocketRuntimeChecks.assertLanguageExecution(getRuntime());
    }

    private static RuntimeInstance checkedRuntime(RuntimeInstance runtime) {
        RuntimeInstance checked = Objects.requireNonNull(runtime, "runtime");
        WebSocketRuntimeChecks.assertLanguageExecution(checked);
        return checked;
    }

    private static String[] validateProtocols(String[] protocols) {
        if (protocols == null || protocols.length == 0) return NO_PROTOCOLS;
        String[] result = protocols.clone();
        for (int index = 0; index < result.length; index++) {
            String protocol = Objects.requireNonNull(result[index], "protocol");
            if (protocol.isEmpty()) {
                throw syntaxError("WebSocket protocol must not be empty");
            }
            for (int offset = 0; offset < protocol.length(); offset++) {
                if (!isTokenCharacter(protocol.charAt(offset))) {
                    throw syntaxError("Invalid WebSocket protocol: " + protocol);
                }
            }
            for (int previous = 0; previous < index; previous++) {
                if (result[previous].equals(protocol)) {
                    throw syntaxError("Duplicate WebSocket protocol: " + protocol);
                }
            }
        }
        return result;
    }

    private static WebSocketUrl parseUrl(RuntimeInstance runtime, String input) {
        String source = preprocessUrlInput(WebSocketScalar.fromString(input, "url"));
        String mapped;
        if (startsWithAsciiIgnoreCase(source, "ws:")) mapped = "http:" + source.substring(3);
        else if (startsWithAsciiIgnoreCase(source, "wss:")) mapped = "https:" + source.substring(4);
        else mapped = source;
        final URL parsed;
        try {
            parsed = new URL(runtime, mapped);
        } catch (JsTypeError error) {
            DOMException syntax = syntaxError("Invalid WebSocket URL");
            syntax.initCause(error);
            throw syntax;
        }
        return parseCanonicalUrl(parsed);
    }

    private static WebSocketUrl parseCanonicalUrl(URL parsed) {
        String href = parsed.getHref();
        if (href.indexOf('#') >= 0) {
            throw syntaxError("WebSocket URL must not contain a fragment");
        }
        if (!parsed.getUsername().isEmpty() || !parsed.getPassword().isEmpty()) {
            throw syntaxError("WebSocket URL must not contain credentials");
        }
        String protocol = parsed.getProtocol();
        if ("http:".equals(protocol)) {
            return new WebSocketUrl(
                    replaceScheme(href, "ws"), replaceScheme(parsed.getOrigin(), "ws"));
        }
        if ("https:".equals(protocol)) {
            return new WebSocketUrl(
                    replaceScheme(href, "wss"), replaceScheme(parsed.getOrigin(), "wss"));
        }
        throw syntaxError("WebSocket URL must use ws, wss, http, or https");
    }

    private static String replaceScheme(String value, String scheme) {
        int colon = value.indexOf(':');
        if (colon < 0) throw new AssertionError("Canonical WebSocket URL lacks a scheme");
        return scheme + value.substring(colon);
    }

    private static String preprocessUrlInput(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && input.charAt(start) <= 0x20) start++;
        while (end > start && input.charAt(end - 1) <= 0x20) end--;
        int kept = 0;
        boolean removed = false;
        for (int index = start; index < end; index++) {
            char current = input.charAt(index);
            if (current == '\t' || current == '\n' || current == '\r') removed = true;
            else kept++;
        }
        if (!removed) {
            return start == 0 && end == input.length() ? input : input.substring(start, end);
        }
        char[] output = new char[kept];
        int written = 0;
        for (int index = start; index < end; index++) {
            char current = input.charAt(index);
            if (current != '\t' && current != '\n' && current != '\r') {
                output[written++] = current;
            }
        }
        return new String(output);
    }

    private static DOMException syntaxError(String message) {
        return new DOMException(message, "SyntaxError");
    }

    private static boolean startsWithAsciiIgnoreCase(String value, String prefix) {
        if (value.length() < prefix.length()) return false;
        for (int index = 0; index < prefix.length(); index++) {
            char actual = value.charAt(index);
            char expected = prefix.charAt(index);
            if (actual >= 'A' && actual <= 'Z') {
                actual = (char) (actual + ('a' - 'A'));
            }
            if (actual != expected) return false;
        }
        return true;
    }

    private static boolean isTokenCharacter(char value) {
        if ((value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')) {
            return true;
        }
        switch (value) {
            case '!':
            case '#':
            case '$':
            case '%':
            case '&':
            case '\'':
            case '*':
            case '+':
            case '-':
            case '.':
            case '^':
            case '_':
            case '`':
            case '|':
            case '~':
                return true;
            default:
                return false;
        }
    }

    private static void checkRange(int size, int offset, int length) {
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", size=" + size);
        }
    }

    private static long saturatingAdd(long current, long increment) {
        if (increment <= 0L) return current;
        return current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment;
    }

    private static void cancelQuietly(WebSocketTransportCall local) {
        if (local == null) return;
        try {
            local.cancel();
        } catch (Throwable error) {
            rethrowIfFatal(error);
        }
    }

    private static boolean isFatal(Throwable error) {
        return error instanceof ThreadDeath
                || error instanceof VirtualMachineError
                || error instanceof LinkageError;
    }

    private static void rethrowIfFatal(Throwable error) {
        if (error instanceof ThreadDeath) throw (ThreadDeath) error;
        if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
        if (error instanceof LinkageError) throw (LinkageError) error;
    }

    private static final class WebSocketUrl {
        final String url;
        final String origin;

        WebSocketUrl(String url, String origin) {
            this.url = url;
            this.origin = origin;
        }
    }

    private static final class OpenEvent extends QueuedWebSocketEvent {
        private final String selectedProtocol;
        private final String selectedExtensions;

        OpenEvent(String selectedProtocol, String selectedExtensions) {
            super(OPEN_EVENT_TYPE);
            this.selectedProtocol = selectedProtocol;
            this.selectedExtensions = selectedExtensions;
        }

        @Override
        void deliver(WebSocket socket) {
            socket.deliverOpen(this, selectedProtocol, selectedExtensions);
        }
    }

    private static final class ClosingEvent extends QueuedWebSocketEvent {
        ClosingEvent() {
            super("websocket-closing");
        }

        @Override
        void deliver(WebSocket socket) {
            socket.deliverClosing();
        }
    }

    private static final class BufferSampleEvent extends QueuedWebSocketEvent {
        BufferSampleEvent() {
            super("websocket-buffer-sample");
        }

        @Override
        void deliver(WebSocket socket) {
            socket.deliverBufferSample();
        }
    }
}
