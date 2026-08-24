package io.github.akisarou.jvmwww.web.websocket.testkit;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.bodies.Blob;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.encoding.Utf8Codec;
import io.github.akisarou.jvmwww.web.events.DOMException;
import io.github.akisarou.jvmwww.web.url.URL;
import io.github.akisarou.jvmwww.web.websocket.CloseEvent;
import io.github.akisarou.jvmwww.web.websocket.MessageEvent;
import io.github.akisarou.jvmwww.web.websocket.WebSocket;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransport;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportCall;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportListener;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic conformance for the transport-independent WebSocket state machine. */
public final class WebSocketConformance {
    private int passed;

    public static void main(String[] args) throws Throwable { new WebSocketConformance().run(); }

    private void run() throws Throwable {
        testCanonicalUrlAndProtocolSnapshot();
        testSynchronousOpenAndStableHandlerOrder();
        testMessageTasksHaveMicrotaskCheckpoints();
        testBinaryDeliveryAndSendOwnership();
        testBufferedAmountSamplingAndPeerClosing();
        testCloseAndFailureOrdering();
        testProtocolNegotiationAndOwnerBoundaries();
        System.out.println("WebSocket core conformance: " + passed + " tests passed");
    }

    private void testCanonicalUrlAndProtocolSnapshot() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            RecordingTransport transport = new RecordingTransport();
            String[] protocols = new String[] {"chat", "super-chat"};
            WebSocket socket = new WebSocket(runtime, transport, "  WS://Example.TEST:80/a/./b/../c d?x=1  ", protocols);
            protocols[0] = "mutated";
            assertEquals("ws://example.test/a/c%20d?x=1", socket.getUrl(), "canonical url");
            assertEquals("chat", transport.request.getProtocol(0), "protocol copied");
            String[] copy = transport.request.copyProtocols();
            copy[0] = "changed";
            assertEquals("chat", transport.request.getProtocol(0), "protocol output isolated");
            WebSocket secure = new WebSocket(runtime, new RecordingTransport(), "https://example.test/socket");
            assertEquals("wss://example.test/socket", secure.getUrl(), "https convenience mapping");
            assertDom("SyntaxError", () -> new WebSocket(runtime, new RecordingTransport(), "ws://example.test/#f"), "fragment");
            assertDom("SyntaxError", () -> new WebSocket(runtime, new RecordingTransport(), "ws://u:p@example.test/"), "credentials");
            assertDom("SyntaxError", () -> new WebSocket(runtime, new RecordingTransport(), "ftp://example.test/"), "scheme");
            assertDom("SyntaxError", () -> new WebSocket(runtime, new RecordingTransport(), "ws://example.test/", new String[] {"chat", "chat"}), "duplicate protocol");
        });
        fixture.runtime.close();
        pass();
    }

    private void testSynchronousOpenAndStableHandlerOrder() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        transport.openSynchronously = true;
        List<String> trace = new ArrayList<String>();
        WebSocket[] socket = new WebSocket[1];
        runTurn(fixture.runtime, runtime -> {
            socket[0] = new WebSocket(runtime, transport, "ws://example.test/", new String[] {"chat"});
            socket[0].addEventListener("open", event -> trace.add("first"));
            socket[0].setOnOpen(event -> trace.add("handler-1"));
            socket[0].addEventListener("open", event -> trace.add("last"));
            socket[0].setOnOpen(event -> trace.add("handler-2"));
            assertEquals(WebSocket.CONNECTING, socket[0].getReadyState(), "sync callback is queued");
            assertDom("InvalidStateError", () -> socket[0].send("early"), "send connecting");
        });
        assertTrue(trace.isEmpty(), "open not inline");
        fixture.executor.runNext();
        assertListEquals(Arrays.asList("first", "handler-2", "last"), trace, "stable handler position");
        runTurn(fixture.runtime, runtime -> {
            assertEquals(WebSocket.OPEN, socket[0].getReadyState(), "open state");
            assertEquals("chat", socket[0].getProtocol(), "selected protocol");
        });
        fixture.runtime.close();
        pass();
    }

    private void testMessageTasksHaveMicrotaskCheckpoints() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        List<String> trace = new ArrayList<String>();
        WebSocket[] socket = new WebSocket[1];
        runTurn(fixture.runtime, runtime -> {
            socket[0] = new WebSocket(runtime, transport, "ws://example.test/");
            socket[0].addEventListener("message", event -> {
                trace.add((String) ((MessageEvent) event).getData());
                runtime.queueMicrotask(owner -> trace.add("micro"));
            });
        });
        transport.listener.onOpen("", "");
        fixture.executor.runNext();
        Thread worker = new Thread(() -> {
            transport.listener.onTextMessage("one");
            transport.listener.onTextMessage("two");
        }, "websocket-worker");
        worker.start();
        worker.join();
        assertEquals(1, fixture.executor.getPendingCallbackCount(), "coalesced wake");
        fixture.executor.runAll();
        assertListEquals(Arrays.asList("one", "micro", "two", "micro"), trace, "checkpoint between messages");
        fixture.runtime.close();
        pass();
    }

    private void testBinaryDeliveryAndSendOwnership() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        List<Object> messages = new ArrayList<Object>();
        WebSocket[] socket = new WebSocket[1];
        runTurn(fixture.runtime, runtime -> {
            socket[0] = new WebSocket(runtime, transport, "ws://example.test/");
            socket[0].addEventListener("message", event -> messages.add(((MessageEvent) event).getData()));
        });
        transport.listener.onOpen("", "");
        fixture.executor.runNext();

        byte[] incoming = new byte[] {1, 2};
        transport.listener.onBinaryMessage(incoming);
        runTurn(fixture.runtime, runtime -> socket[0].setBinaryType(WebSocket.BINARY_TYPE_ARRAYBUFFER));
        fixture.executor.runNext();
        assertSame(incoming, messages.get(0), "arraybuffer owns incoming bytes");

        byte[] outgoing = new byte[] {9, 3, 4, 8};
        runTurn(fixture.runtime, runtime -> {
            socket[0].send(outgoing, 1, 2);
            outgoing[1] = 7;
            assertArrayEquals(new byte[] {3, 4}, transport.call.lastBinary.copyBytes(), "send defensive copy");
            Blob blob = new Blob(runtime, "xy");
            socket[0].send(blob);
            assertArrayEquals("xy".getBytes(StandardCharsets.UTF_8), transport.call.lastBinary.copyBytes(), "blob snapshot");
            assertThrows(JsTypeError.class, () -> socket[0].setBinaryType("bytes"), "binaryType validation");
        });
        fixture.runtime.close();
        pass();
    }

    private void testBufferedAmountSamplingAndPeerClosing() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        WebSocket[] socket = new WebSocket[1];
        runTurn(fixture.runtime, runtime -> socket[0] = new WebSocket(runtime, transport, "ws://example.test/"));
        transport.listener.onOpen("", "");
        fixture.executor.runNext();
        runTurn(fixture.runtime, runtime -> {
            socket[0].send("abc");
            assertEquals(3L, socket[0].getBufferedAmount(), "send increments cache");
        });
        transport.call.queuedBytes = 0L;
        fixture.executor.runNext();
        runTurn(fixture.runtime, runtime -> assertEquals(0L, socket[0].getBufferedAmount(), "sample drains cache"));

        transport.listener.onClosing();
        int sends = transport.call.binarySends;
        runTurn(fixture.runtime, runtime -> {
            socket[0].send(new byte[] {1, 2, 3});
            assertEquals(sends, transport.call.binarySends, "peer closing suppresses transport send");
            assertEquals(3L, socket[0].getBufferedAmount(), "discarded send remains buffered");
        });
        fixture.executor.runNext();
        runTurn(fixture.runtime, runtime -> assertEquals(WebSocket.CLOSING, socket[0].getReadyState(), "queued closing state"));
        fixture.runtime.close();
        pass();
    }

    private void testCloseAndFailureOrdering() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        List<String> trace = new ArrayList<String>();
        WebSocket[] socket = new WebSocket[1];
        runTurn(fixture.runtime, runtime -> {
            socket[0] = new WebSocket(runtime, transport, "ws://example.test/");
            socket[0].addEventListener("error", event -> trace.add("error"));
            socket[0].addEventListener("close", event -> {
                trace.add("close:" + ((CloseEvent) event).getCode());
                runtime.queueMicrotask(owner -> trace.add("micro"));
            });
        });
        transport.listener.onOpen("", "");
        fixture.executor.runNext();
        runTurn(fixture.runtime, runtime -> {
            assertDom("InvalidAccessError", () -> socket[0].close(1001), "invalid close code");
            assertDom("SyntaxError", () -> socket[0].close(3000, repeat('x', 124)), "long reason");
        });
        transport.listener.onFailure(new IllegalStateException("boom"));
        fixture.executor.runNext();
        assertListEquals(Arrays.asList("error", "close:1006", "micro"), trace, "error close same task");
        runTurn(fixture.runtime, runtime -> assertEquals(WebSocket.CLOSED, socket[0].getReadyState(), "closed after failure"));
        fixture.runtime.close();
        pass();
    }

    private void testProtocolNegotiationAndOwnerBoundaries() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        WebSocket[] socket = new WebSocket[1];
        runTurn(fixture.runtime, runtime -> socket[0] = new WebSocket(runtime, transport, "ws://example.test/", new String[] {"chat"}));
        assertThrows(IllegalStateException.class, socket[0]::getUrl, "owner confinement");
        transport.listener.onOpen("other", "");
        fixture.executor.runNext();
        assertTrue(transport.call.cancelled, "unoffered protocol cancels exact call");
        runTurn(fixture.runtime, runtime -> assertEquals(WebSocket.CLOSED, socket[0].getReadyState(), "bad negotiation closes"));

        Fixture other = new Fixture();
        URL[] otherUrl = new URL[1];
        runTurn(other.runtime, runtime -> otherUrl[0] = new URL(runtime, "https://example.test/"));
        runTurn(fixture.runtime, runtime -> assertThrows(IllegalArgumentException.class,
                () -> new WebSocket(runtime, new RecordingTransport(), otherUrl[0]), "cross-runtime URL"));
        fixture.runtime.close();
        other.runtime.close();
        pass();
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try { task.execute(runtime); }
        finally { runtime.leaveHostTurn(); }
    }
    private void pass() { passed++; }
    private static String repeat(char c, int count) { char[] chars = new char[count]; Arrays.fill(chars, c); return new String(chars); }
    private static void assertDom(String name, ThrowingRunnable action, String label) {
        try { action.run(); }
        catch (DOMException error) {
            if (name.equals(error.getName())) return;
            throw new AssertionError(label + ": expected " + name + ", got " + error.getName());
        } catch (Throwable error) { throw new AssertionError(label + ": wrong exception " + error, error); }
        throw new AssertionError(label + ": expected DOMException " + name);
    }
    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable action, String label) {
        try { action.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(label + ": wrong exception " + error, error);
        }
        throw new AssertionError(label + ": expected " + expected.getName());
    }
    private static void assertTrue(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static void assertSame(Object expected, Object actual, String label) { if (expected != actual) throw new AssertionError(label); }
    private static void assertEquals(int expected, int actual, String label) { if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual); }
    private static void assertEquals(long expected, long actual, String label) { if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual); }
    private static void assertEquals(String expected, Object actual, String label) { if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual); }
    private static void assertArrayEquals(byte[] expected, byte[] actual, String label) { if (!Arrays.equals(expected, actual)) throw new AssertionError(label); }
    private static void assertListEquals(List<String> expected, List<String> actual, String label) { if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual); }
    private interface ThrowingRunnable { void run() throws Throwable; }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }
    private static final class RecordingTransport implements WebSocketTransport {
        final RecordingCall call = new RecordingCall();
        WebSocketTransportRequest request;
        WebSocketTransportListener listener;
        boolean openSynchronously;
        @Override public WebSocketTransportCall start(WebSocketTransportRequest request, WebSocketTransportListener listener) {
            this.request = request;
            this.listener = listener;
            if (openSynchronously) listener.onOpen("chat", "permessage-deflate");
            return call;
        }
    }
    private static final class RecordingCall implements WebSocketTransportCall {
        boolean acceptSends = true;
        boolean cancelled;
        int binarySends;
        BufferedBodySnapshot lastBinary;
        long queuedBytes;
        @Override public boolean sendText(String text) {
            if (!acceptSends) return false;
            queuedBytes += Utf8Codec.encodedLength(text);
            return true;
        }
        @Override public boolean sendBinary(BufferedBodySnapshot data) {
            binarySends++;
            lastBinary = data;
            if (!acceptSends) return false;
            queuedBytes += data.getSize();
            return true;
        }
        @Override public long getQueuedByteCount() { return queuedBytes; }
        @Override public boolean close(int code, String reason) { return true; }
        @Override public void cancel() { cancelled = true; }
    }
}
