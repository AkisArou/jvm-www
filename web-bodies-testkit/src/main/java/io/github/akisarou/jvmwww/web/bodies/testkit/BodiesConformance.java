package io.github.akisarou.jvmwww.web.bodies.testkit;

import io.github.akisarou.jvmwww.runtime.JsPromise;
import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.bodies.Blob;
import io.github.akisarou.jvmwww.web.bodies.BlobBuilder;
import io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot;
import io.github.akisarou.jvmwww.web.bodies.File;
import io.github.akisarou.jvmwww.web.bodies.FormData;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Deterministic conformance for immutable buffered Blob, File, and FormData. */
public final class BodiesConformance {
    private static final String BOUNDARY = "jvmwwwBoundary0123456789abcdef";
    private int passed;

    public static void main(String[] args) throws Throwable {
        new BodiesConformance().run();
    }

    private void run() throws Throwable {
        testSegmentedBlobConstructionAndReads();
        testBlobSliceAndTypeRules();
        testFileMetadataAndScalarName();
        testFormDataEntrySemantics();
        testMultipartEncoding();
        testSnapshotsAreIndependent();
        testBoundaryValidation();
        testOwnerConfinementAndRuntimeIsolation();
        System.out.println("Web bodies conformance: " + passed + " tests passed");
    }

    private void testSegmentedBlobConstructionAndReads() throws Throwable {
        Fixture fixture = new Fixture();
        byte[] source = new byte[] {'a', 'b'};
        Blob[] blob = new Blob[1];
        JsPromise[] bytes = new JsPromise[1];
        JsPromise[] text = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            Blob tail = new Blob(runtime, "!");
            BlobBuilder builder = new BlobBuilder(runtime, 3);
            builder.appendBytes(source).appendText("\ud83d\udca9").appendBlob(tail);
            blob[0] = builder.build("TEXT/PLAIN");
            source[0] = 'z';
            assertEquals(7L, blob[0].getSize(), "UTF-8 byte size");
            assertEquals("text/plain", blob[0].getType(), "ASCII lowercase type");
            bytes[0] = blob[0].arrayBuffer();
            text[0] = blob[0].text();
            assertTrue(bytes[0].isPending(), "Blob read waits for microtask checkpoint");
        });
        assertArrayEquals(
                new byte[] {'a', 'b', (byte) 0xf0, (byte) 0x9f, (byte) 0x92, (byte) 0xa9, '!'},
                (byte[]) bytes[0].getReferencePayload(),
                "Blob bytes");
        assertEquals("ab\ud83d\udca9!", text[0].getReferencePayload(), "Blob text");
        fixture.runtime.close();
        pass();
    }

    private void testBlobSliceAndTypeRules() throws Throwable {
        Fixture fixture = new Fixture();
        JsPromise[] bytes = new JsPromise[1];
        runTurn(fixture.runtime, runtime -> {
            Blob source = new BlobBuilder(runtime, 3)
                    .appendText("ab")
                    .appendText("cd")
                    .appendText("ef")
                    .build("X/ORIGINAL");
            Blob slice = source.slice(-5L, -1L, "APPLICATION/OCTET-STREAM");
            assertEquals(4L, slice.getSize(), "negative slice bounds");
            assertEquals("application/octet-stream", slice.getType(), "slice type");
            assertEquals("", source.slice().getType(), "slice default type is empty");
            assertEquals("", new Blob(runtime, new byte[0], "text/\u0080").getType(), "invalid type is empty");
            bytes[0] = slice.bytes();
        });
        assertArrayEquals(new byte[] {'b', 'c', 'd', 'e'},
                (byte[]) bytes[0].getReferencePayload(), "slice bytes");
        fixture.runtime.close();
        pass();
    }

    private void testFileMetadataAndScalarName() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            File file = new File(runtime, "data", "a\ud800b.txt", "TEXT/PLAIN", 1234L);
            assertEquals("a\ufffdb.txt", file.getName(), "File name is a scalar string");
            assertEquals("text/plain", file.getType(), "File type");
            assertEquals(1234L, file.getLastModified(), "lastModified");
            assertEquals("", file.getWebkitRelativePath(), "constructor relative path");
            assertTrue(file instanceof Blob, "File is a Blob");
        });
        fixture.runtime.close();
        pass();
    }

    private void testFormDataEntrySemantics() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            FormData data = new FormData(runtime, () -> BOUNDARY);
            data.append("a", "1");
            data.append("a", "2");
            data.append("b", "3");
            assertEquals("1", data.get("a"), "get first value");
            assertEquals(2, data.getAll("a").length, "getAll duplicates");
            data.set("a", "x");
            assertEquals(2, data.size(), "set removes later duplicates");
            assertEquals("x", data.getValue(0), "set preserves first position");
            data.delete("b");
            assertTrue(!data.has("b"), "delete removes all values");

            Blob blob = new Blob(runtime, "blob-data", "TEXT/PLAIN");
            data.append("blob", blob);
            File converted = data.getFileValue(1);
            assertEquals("blob", converted.getName(), "Blob default filename");
            assertEquals("text/plain", converted.getType(), "Blob type preserved");
            File file = new File(runtime, "f", "original.txt", "text/plain", 9L);
            data.append("file", file);
            assertSame(file, data.getValue(2), "File identity retained without filename override");
            data.append("renamed", file, "new.txt");
            assertEquals("new.txt", data.getFileValue(3).getName(), "filename override");
            assertTrue(data.getFileValue(3) != file, "filename override creates a File");
        });
        fixture.runtime.close();
        pass();
    }

    private void testMultipartEncoding() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            FormData data = new FormData(runtime, () -> BOUNDARY);
            data.append("a\n\"", "x\ny");
            data.append(
                    "upload",
                    new File(runtime, new byte[] {1, 2}, "f\"\n.txt", "TEXT/PLAIN", 0L));
            BufferedBodySnapshot snapshot = data.snapshot();
            assertEquals(
                    "multipart/form-data; boundary=" + BOUNDARY,
                    snapshot.getContentType(),
                    "multipart content type");
            String expected =
                    "--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"a%0D%0A%22\"\r\n\r\n"
                    + "x\r\ny\r\n"
                    + "--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"upload\"; filename=\"f%22%0A.txt\"\r\n"
                    + "Content-Type: text/plain\r\n\r\n";
            byte[] prefix = expected.getBytes(StandardCharsets.UTF_8);
            byte[] suffix = ("\r\n--" + BOUNDARY + "--\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] wanted = new byte[prefix.length + 2 + suffix.length];
            System.arraycopy(prefix, 0, wanted, 0, prefix.length);
            wanted[prefix.length] = 1;
            wanted[prefix.length + 1] = 2;
            System.arraycopy(suffix, 0, wanted, prefix.length + 2, suffix.length);
            assertArrayEquals(wanted, snapshot.copyBytes(), "exact multipart bytes");
        });
        fixture.runtime.close();
        pass();
    }

    private void testSnapshotsAreIndependent() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            Blob blob = new Blob(runtime, new byte[] {1, 2, 3}, "application/test");
            BufferedBodySnapshot snapshot = blob.snapshot();
            byte[] first = snapshot.copyBytes();
            first[0] = 9;
            assertArrayEquals(new byte[] {1, 2, 3}, snapshot.copyBytes(), "snapshot copy isolation");

            FormData data = new FormData(runtime, () -> BOUNDARY);
            data.append("a", "one");
            BufferedBodySnapshot before = data.snapshot();
            data.set("a", "two");
            String body = new String(before.copyBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\r\none\r\n"), "snapshot freezes FormData state");
            assertTrue(!body.contains("\r\ntwo\r\n"), "later mutation cannot alter snapshot");
        });
        fixture.runtime.close();
        pass();
    }

    private void testBoundaryValidation() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            FormData shortBoundary = new FormData(runtime, () -> "short");
            assertThrows(JsTypeError.class, shortBoundary::snapshot, "short boundary rejected");
            FormData invalidBoundary = new FormData(
                    runtime,
                    () -> "boundary-with-invalid-space-1234 ");
            assertThrows(JsTypeError.class, invalidBoundary::snapshot, "invalid boundary rejected");
        });
        fixture.runtime.close();
        pass();
    }

    private void testOwnerConfinementAndRuntimeIsolation() throws Throwable {
        Fixture fixture = new Fixture();
        Blob[] blob = new Blob[1];
        FormData[] data = new FormData[1];
        runTurn(fixture.runtime, runtime -> {
            blob[0] = new Blob(runtime, "x");
            data[0] = new FormData(runtime, () -> BOUNDARY);
        });
        assertThrows(IllegalStateException.class, blob[0]::getSize, "Blob outside turn");
        assertThrows(IllegalStateException.class, data[0]::size, "FormData outside turn");

        Fixture other = new Fixture();
        runTurn(fixture.runtime, owner -> {
            other.runtime.enterHostTurn();
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new BlobBuilder(other.runtime).appendBlob(blob[0]),
                        "cross-runtime Blob part rejected");
                FormData otherData = new FormData(other.runtime, () -> BOUNDARY);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> otherData.append("x", blob[0]),
                        "cross-runtime FormData Blob rejected");
            } finally {
                other.runtime.leaveHostTurn();
            }
        });
        fixture.runtime.close();
        other.runtime.close();
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

    private void pass() { passed++; }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) throw new AssertionError(label);
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

    private static void assertEquals(String expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
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

    private static void assertThrows(
            Class<? extends Throwable> expected,
            ThrowingRunnable action,
            String label) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(label + ": wrong exception " + error, error);
        }
        throw new AssertionError(label + ": expected " + expected.getName());
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class Fixture {
        final ManualOwnerExecutor executor = new ManualOwnerExecutor();
        final CollectingErrorReporter errors = new CollectingErrorReporter();
        final RuntimeInstance runtime = new RuntimeInstance(executor, errors);
    }
}
