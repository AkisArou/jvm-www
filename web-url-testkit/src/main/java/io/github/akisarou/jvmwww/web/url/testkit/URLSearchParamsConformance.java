package io.github.akisarou.jvmwww.web.url.testkit;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.url.URLSearchParams;
import java.util.Arrays;

/** Deterministic conformance for URLSearchParams and form URL encoding. */
public final class URLSearchParamsConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new URLSearchParamsConformance().run();
    }

    private void run() throws Throwable {
        testStringParsingAndPercentDecoding();
        testMutationAndOptionalValueMatching();
        testFormSerialization();
        testScalarValueConversion();
        testStableCodeUnitSort();
        testCopyIsIndependent();
        testOwnerConfinement();
        System.out.println("URLSearchParams conformance: " + passed + " tests passed");
    }

    private void testStringParsingAndPercentDecoding() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URLSearchParams params = new URLSearchParams(
                    runtime,
                    "?a=1&&b=hello+world&c=%E2%89%A1&x=%25%s%1G&empty&=v"
                            + "&bom=%EF%BB%BF&bad=%E2%28%A1");
            assertEquals(8, params.size(), "empty sequences skipped");
            assertEquals("1", params.get("a"), "first value");
            assertEquals("hello world", params.get("b"), "plus becomes space");
            assertEquals("\u2261", params.get("c"), "UTF-8 percent decoding");
            assertEquals("%%s%1G", params.get("x"), "invalid percent escapes preserved");
            assertEquals("", params.get("empty"), "missing equals has empty value");
            assertEquals("v", params.get(""), "empty name retained");
            assertEquals("\ufeff", params.get("bom"), "form decoding preserves BOM code point");
            assertEquals("\ufffd(\ufffd", params.get("bad"), "malformed UTF-8 replacement boundaries");
            assertEquals(null, params.get("missing"), "missing name returns null");
        });
        fixture.runtime.close();
        pass();
    }

    private void testMutationAndOptionalValueMatching() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URLSearchParams params = new URLSearchParams(runtime, "a=1&a=2&b=3");
            params.append("a", "4");
            assertArrayEquals(new String[] {"1", "2", "4"}, params.getAll("a"), "append order");
            params.set("a", "x");
            assertEquals("a=x&b=3", params.toString(), "set keeps first position and removes duplicates");
            params.append("a", "x");
            params.append("a", "y");
            assertTrue(params.has("a", "x"), "has optional value");
            params.delete("a", "x");
            assertEquals("b=3&a=y", params.toString(), "delete optional value removes exact pairs");
            assertTrue(!params.has("a", "x"), "deleted pair absent");
            assertTrue(params.has("a"), "other name pair remains");
            params.delete("a");
            assertEquals("b=3", params.toString(), "delete name removes every pair");
        });
        fixture.runtime.close();
        pass();
    }

    private void testFormSerialization() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URLSearchParams params = new URLSearchParams(runtime);
            params.append("q", "1+1 \u2261 2%20\u203d");
            params.append("safe*.-_", "AZaz09");
            assertEquals(
                    "q=1%2B1+%E2%89%A1+2%2520%E2%80%BD&safe*.-_=AZaz09",
                    params.toString(),
                    "form percent-encode set and plus spaces");
        });
        fixture.runtime.close();
        pass();
    }

    private void testScalarValueConversion() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URLSearchParams params = new URLSearchParams(runtime);
            params.append("\ud800", "\udc00");
            assertEquals("\ufffd", params.getName(0), "lone high surrogate becomes replacement");
            assertEquals("\ufffd", params.getValue(0), "lone low surrogate becomes replacement");
            assertEquals("%EF%BF%BD=%EF%BF%BD", params.toString(), "replacement serialized as UTF-8");
        });
        fixture.runtime.close();
        pass();
    }

    private void testStableCodeUnitSort() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URLSearchParams params = new URLSearchParams(runtime, "b=3&a=1&a=2&A=0");
            params.sort();
            assertEquals("A=0&a=1&a=2&b=3", params.toString(), "UTF-16 code-unit order");
            assertArrayEquals(new String[] {"1", "2"}, params.getAll("a"), "equal-name order is stable");
        });
        fixture.runtime.close();
        pass();
    }

    private void testCopyIsIndependent() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URLSearchParams original = new URLSearchParams(runtime, "a=1&a=2");
            URLSearchParams copy = new URLSearchParams(original);
            original.set("a", "changed");
            copy.append("b", "3");
            assertEquals("a=changed", original.toString(), "original mutates independently");
            assertEquals("a=1&a=2&b=3", copy.toString(), "copy retains its own list");
        });
        fixture.runtime.close();
        pass();
    }

    private void testOwnerConfinement() throws Throwable {
        Fixture fixture = new Fixture();
        URLSearchParams[] params = new URLSearchParams[1];
        runTurn(fixture.runtime, runtime -> params[0] = new URLSearchParams(runtime, "a=1"));
        assertThrows(IllegalStateException.class, params[0]::size, "access outside active owner turn");
        fixture.runtime.close();
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

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertArrayEquals(String[] expected, String[] actual, String label) {
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
