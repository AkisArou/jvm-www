package io.github.akisarou.jvmwww.web.websocket.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.websocket.WebSocket;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransport;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportCall;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportListener;
import io.github.akisarou.jvmwww.web.websocket.WebSocketTransportRequest;

/** Conformance for RuntimeInstance ownership of active WebSocket transports. */
public final class WebSocketRuntimeOwnershipConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new WebSocketRuntimeOwnershipConformance().run();
    }

    private void run() throws Throwable {
        testRuntimeCloseCancelsConnectingSocketWithoutEvents();
        testAcceptedClosingHandshakeRemainsRuntimeOwned();
        testTerminalCallbackReleasesRuntimeOwnershipBeforeDelivery();
        System.out.println("WebSocket runtime ownership: " + passed + " tests passed");
    }

    private void testRuntimeCloseCancelsConnectingSocketWithoutEvents() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime ->
                new WebSocket(runtime, transport, "ws://example.test/open"));

        fixture.runtime.close();
        assertEquals(1, transport.call.cancelCount, "runtime close cancels connecting socket");
        fixture.executor.runAll();
        pass();
    }

    private void testAcceptedClosingHandshakeRemainsRuntimeOwned() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        final WebSocket[] socket = new WebSocket[1];
        runTurn(fixture.runtime, runtime ->
                socket[0] = new WebSocket(runtime, transport, "ws://example.test/closing"));
        transport.listener.onOpen("", "");
        fixture.executor.runAll();
        runTurn(fixture.runtime, runtime -> socket[0].close(1000, "done"));

        assertEquals(1, transport.call.closeCount, "transport close handshake started");
        fixture.runtime.close();
        assertEquals(
                1,
                transport.call.cancelCount,
                "runtime close cancels unfinished close handshake");
        pass();
    }

    private void testTerminalCallbackReleasesRuntimeOwnershipBeforeDelivery() throws Throwable {
        Fixture fixture = new Fixture();
        RecordingTransport transport = new RecordingTransport();
        runTurn(fixture.runtime, runtime ->
                new WebSocket(runtime, transport, "ws://example.test/done"));

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                transport.listener.onClosed(1000, "done", true);
            }
        }, "websocket-terminal");
        worker.start();
        worker.join();

        fixture.runtime.close();
        assertEquals(
                0,
                transport.call.cancelCount,
                "terminal callback released the completed transport before owner delivery");
        fixture.executor.runAll();
        pass();
    }

    private static void runTurn(RuntimeInstance runtime, RuntimeTask task) throws Throwable {
        runtime.enterHostTurn();
        try {
            task.execute(runtime);
        } finally {
            runtime.leaveHostTurn();
        }
    }

    private void pass() {
        passed++;
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }

    private static final class RecordingTransport implements WebSocketTransport {
        final RecordingCall call = new RecordingCall();
        WebSocketTransportListener listener;

        @Override
        public WebSocketTransportCall start(
                WebSocketTransportRequest request,
                WebSocketTransportListener listener) {
            this.listener = listener;
            return call;
        }
    }

    private static final class RecordingCall implements WebSocketTransportCall {
        int closeCount;
        int cancelCount;

        @Override
        public boolean sendText(String text) {
            return true;
        }

        @Override
        public boolean sendBinary(BufferedBodySnapshot data) {
            return true;
        }

        @Override
        public long getQueuedByteCount() {
            return 0L;
        }

        @Override
        public boolean close(int code, String reason) {
            closeCount++;
            return true;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }
    }
}
