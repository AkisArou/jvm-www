package io.github.akisarou.jvmwww.web.websocket.okhttp.testkit;

import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportCall;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportListener;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportTestSupport;
import io.github.akisarou.jvmwww.web.websocket.okhttp.OkHttpWebSocketTransport;
import java.nio.ByteBuffer;
import java.util.Arrays;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Deterministic conformance for the OkHttp WebSocket transport boundary. */
public final class OkHttpWebSocketTransportConformance {
    private int passed;

    public static void main(String[] args) {
        new OkHttpWebSocketTransportConformance().run();
    }

    private void run() {
        testRequestAndProtocolSnapshot();
        testNoProtocolHeaderForEmptyOffer();
        testSynchronousOpenBindsExactSocket();
        testTextAndBinaryCallbacks();
        testOutboundMappingAndSingleBinaryCopy();
        testQueueCloseAndCancelMapping();
        testClosingClosedAndFailureMapping();
        testWrongSocketAndInvalidUpgradeFail();
        System.out.println("OkHttp WebSocket transport conformance: " + passed + " tests passed");
    }

    private void testRequestAndProtocolSnapshot() {
        Fixture fixture = new Fixture();
        WebSocketTransportCall call = fixture.transport.start(
                WebSocketTransportTestSupport.request(
                        "wss://example.test/socket?x=1",
                        "chat",
                        "superchat"),
                fixture.listener);

        assertSame(fixture.socket, fixture.factory.socket, "factory socket");
        assertEquals("wss://example.test/socket?x=1", fixture.factory.request.url(), "request URL");
        assertEquals(1, fixture.factory.request.headers().size(), "protocol header count");
        assertEquals(
                "Sec-WebSocket-Protocol",
                fixture.factory.request.headers().name(0),
                "protocol header name");
        assertEquals(
                "chat, superchat",
                fixture.factory.request.headers().value(0),
                "protocol header value");
        assertNotSame(fixture.socket, call, "bridge is not the platform socket");
        pass();
    }

    private void testNoProtocolHeaderForEmptyOffer() {
        Fixture fixture = new Fixture();
        fixture.transport.start(
                WebSocketTransportTestSupport.request("ws://example.test/socket"),
                fixture.listener);
        assertEquals(0, fixture.factory.request.headers().size(), "empty offer has no header");
        pass();
    }

    private void testSynchronousOpenBindsExactSocket() {
        Fixture fixture = new Fixture();
        fixture.factory.synchronousOpen = new Response(
                101,
                new Headers(
                        new String[] {
                            "sec-websocket-protocol",
                            "Sec-WebSocket-Extensions",
                            "sec-websocket-extensions"
                        },
                        new String[] {
                            "chat",
                            "permessage-deflate",
                            "x-test"
                        }));
        fixture.transport.start(
                WebSocketTransportTestSupport.request(
                        "wss://example.test/socket",
                        "chat"),
                fixture.listener);

        assertEquals(1, fixture.listener.openCount, "synchronous open count");
        assertEquals("chat", fixture.listener.protocol, "selected protocol");
        assertEquals(
                "permessage-deflate, x-test",
                fixture.listener.extensions,
                "combined extensions");
        assertSame(fixture.socket, fixture.factory.socket, "synchronous callback socket identity");
        pass();
    }

    private void testTextAndBinaryCallbacks() {
        Fixture fixture = new Fixture();
        fixture.transport.start(
                WebSocketTransportTestSupport.request("wss://example.test/socket"),
                fixture.listener);

        fixture.factory.listener.onMessage(fixture.socket, "hello");
        byte[] input = new byte[] {1, 2, 3, 4};
        fixture.factory.listener.onMessage(fixture.socket, ByteString.testOwned(input));
        input[0] = 9;

        assertEquals("hello", fixture.listener.text, "text callback");
        assertArrayEquals(new byte[] {1, 2, 3, 4}, fixture.listener.binary, "owned binary callback");
        pass();
    }

    private void testOutboundMappingAndSingleBinaryCopy() {
        Fixture fixture = new Fixture();
        WebSocketTransportCall call = fixture.transport.start(
                WebSocketTransportTestSupport.request("wss://example.test/socket"),
                fixture.listener);

        assertTrue(call.sendText("hello"), "text send accepted");
        assertEquals("hello", fixture.socket.sentText, "text send payload");

        ByteString.ofByteBufferCalls = 0;
        ByteString.ofByteArrayCalls = 0;
        byte[] owned = new byte[] {5, 6, 7};
        BufferedBodySnapshot snapshot = BufferedBodySnapshot.fromOwnedBytes(owned, null);
        ByteBuffer view = snapshot.asReadOnlyByteBuffer();
        assertTrue(view.isReadOnly(), "snapshot ByteBuffer is read-only");
        assertTrue(call.sendBinary(snapshot), "binary send accepted");

        assertArrayEquals(new byte[] {5, 6, 7}, fixture.socket.sentBinary.toByteArray(), "binary send payload");
        assertEquals(1, ByteString.ofByteBufferCalls, "one ByteBuffer-to-ByteString copy");
        assertEquals(0, ByteString.ofByteArrayCalls, "no byte-array ByteString recopy");
        pass();
    }

    private void testQueueCloseAndCancelMapping() {
        Fixture fixture = new Fixture();
        WebSocketTransportCall call = fixture.transport.start(
                WebSocketTransportTestSupport.request("wss://example.test/socket"),
                fixture.listener);
        fixture.socket.queueSize = 41L;
        assertEquals(41L, call.getQueuedByteCount(), "queue size mapping");

        assertTrue(call.close(0, ""), "default close accepted");
        assertEquals(1000, fixture.socket.closeCode, "default close code mapping");
        assertEquals("", fixture.socket.closeReason, "default close reason");

        assertTrue(call.close(3001, "later"), "custom close accepted");
        assertEquals(3001, fixture.socket.closeCode, "custom close code");
        assertEquals("later", fixture.socket.closeReason, "custom close reason");

        call.cancel();
        assertTrue(fixture.socket.cancelled, "exact socket cancellation");
        pass();
    }

    private void testClosingClosedAndFailureMapping() {
        Fixture fixture = new Fixture();
        fixture.transport.start(
                WebSocketTransportTestSupport.request("wss://example.test/socket"),
                fixture.listener);

        fixture.factory.listener.onClosing(fixture.socket, 1000, "bye");
        assertEquals(1, fixture.listener.closingCount, "closing callback");

        fixture.factory.listener.onClosed(fixture.socket, 1001, "away");
        assertEquals(1001, fixture.listener.closeCode, "closed code");
        assertEquals("away", fixture.listener.closeReason, "closed reason");
        assertTrue(fixture.listener.wasClean, "OkHttp onClosed is clean");

        IllegalStateException failure = new IllegalStateException("boom");
        fixture.factory.listener.onFailure(fixture.socket, failure, null);
        assertSame(failure, fixture.listener.failure, "failure identity");
        pass();
    }

    private void testWrongSocketAndInvalidUpgradeFail() {
        Fixture mismatch = new Fixture();
        mismatch.transport.start(
                WebSocketTransportTestSupport.request("wss://example.test/socket"),
                mismatch.listener);
        FakeWebSocket wrong = new FakeWebSocket();
        mismatch.factory.listener.onMessage(wrong, "should-not-deliver");
        assertTrue(wrong.cancelled, "wrong callback socket cancelled");
        assertEquals(null, mismatch.listener.text, "wrong callback message suppressed");
        assertTrue(
                mismatch.listener.failure instanceof IllegalStateException,
                "wrong callback reports identity failure");

        Fixture invalid = new Fixture();
        invalid.transport.start(
                WebSocketTransportTestSupport.request("wss://example.test/socket"),
                invalid.listener);
        invalid.factory.listener.onOpen(
                invalid.socket,
                new Response(200, new Headers(new String[0], new String[0])));
        assertTrue(invalid.socket.cancelled, "invalid upgrade socket cancelled");
        assertTrue(
                invalid.listener.failure instanceof IllegalStateException,
                "invalid upgrade reports failure");
        assertEquals(0, invalid.listener.openCount, "invalid upgrade not opened");
        pass();
    }

    private void pass() {
        passed++;
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected same object");
        }
    }

    private static void assertNotSame(Object first, Object second, String label) {
        if (first == second) throw new AssertionError(label);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String label) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + Arrays.toString(expected)
                            + ", got " + Arrays.toString(actual));
        }
    }

    private static final class Fixture {
        final FakeWebSocket socket = new FakeWebSocket();
        final FakeFactory factory = new FakeFactory(socket);
        final RecordingListener listener = new RecordingListener();
        final OkHttpWebSocketTransport transport = new OkHttpWebSocketTransport(factory);
    }

    private static final class FakeFactory implements WebSocket.Factory {
        final FakeWebSocket socket;
        Request request;
        WebSocketListener listener;
        Response synchronousOpen;

        FakeFactory(FakeWebSocket socket) {
            this.socket = socket;
        }

        @Override
        public WebSocket newWebSocket(Request request, WebSocketListener listener) {
            this.request = request;
            this.listener = listener;
            if (synchronousOpen != null) listener.onOpen(socket, synchronousOpen);
            return socket;
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        long queueSize;
        String sentText;
        ByteString sentBinary;
        int closeCode;
        String closeReason;
        boolean cancelled;

        @Override
        public long queueSize() {
            return queueSize;
        }

        @Override
        public boolean send(String text) {
            sentText = text;
            return true;
        }

        @Override
        public boolean send(ByteString bytes) {
            sentBinary = bytes;
            return true;
        }

        @Override
        public boolean close(int code, String reason) {
            closeCode = code;
            closeReason = reason;
            return true;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private static final class RecordingListener implements WebSocketTransportListener {
        int openCount;
        String protocol;
        String extensions;
        String text;
        byte[] binary;
        int closingCount;
        int closeCode;
        String closeReason;
        boolean wasClean;
        Throwable failure;

        @Override
        public void onOpen(String protocol, String extensions) {
            openCount++;
            this.protocol = protocol;
            this.extensions = extensions;
        }

        @Override
        public void onTextMessage(String text) {
            this.text = text;
        }

        @Override
        public void onBinaryMessage(byte[] bytes) {
            this.binary = bytes;
        }

        @Override
        public void onClosing() {
            closingCount++;
        }

        @Override
        public void onClosed(int code, String reason, boolean wasClean) {
            closeCode = code;
            closeReason = reason;
            this.wasClean = wasClean;
        }

        @Override
        public void onFailure(Throwable error) {
            failure = error;
        }
    }
}
