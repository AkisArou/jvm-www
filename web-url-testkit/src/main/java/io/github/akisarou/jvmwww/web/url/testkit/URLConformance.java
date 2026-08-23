package io.github.akisarou.jvmwww.web.url.testkit;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.runtime.RuntimeTask;
import io.github.akisarou.jvmwww.testkit.CollectingErrorReporter;
import io.github.akisarou.jvmwww.testkit.ManualOwnerExecutor;
import io.github.akisarou.jvmwww.web.url.URL;
import io.github.akisarou.jvmwww.web.url.URLSearchParams;

/** Deterministic conformance for the first special http/https URL profile. */
public final class URLConformance {
    private int passed;

    public static void main(String[] args) throws Throwable {
        new URLConformance().run();
    }

    private void run() throws Throwable {
        testAbsoluteParsingAndCanonicalSerialization();
        testRelativeResolution();
        testComponentGettersAndSetters();
        testLiveSearchParamsAssociation();
        testEmptyQueryAndFragmentMarkers();
        testSelectedHostProfileAndParseHelpers();
        testHrefReplacementKeepsQueryObjectIdentity();
        testOwnerConfinementAndRuntimeIsolation();
        System.out.println("URL conformance: " + passed + " tests passed");
    }

    private void testAbsoluteParsingAndCanonicalSerialization() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URL url = new URL(
                    runtime,
                    " \nHTTP://user:p@Example.COM:80/a/./b/%2e%2e/c d"
                            + "?x=a b&y='z'#f g ");
            assertEquals(
                    "http://user:p@example.com/a/c%20d?x=a%20b&y=%27z%27#f%20g",
                    url.getHref(),
                    "canonical href");
            assertEquals("http://example.com", url.getOrigin(), "origin omits credentials");
            assertEquals("http:", url.getProtocol(), "protocol");
            assertEquals("user", url.getUsername(), "username");
            assertEquals("p", url.getPassword(), "password");
            assertEquals("example.com", url.getHost(), "default port omitted");
            assertEquals("/a/c%20d", url.getPathname(), "dot segments and path encoding");
            assertEquals("?x=a%20b&y=%27z%27", url.getSearch(), "special query encoding");
            assertEquals("#f%20g", url.getHash(), "fragment encoding");
            assertEquals(url.getHref(), url.toJSON(), "JSON serialization");
        });
        fixture.runtime.close();
        pass();
    }

    private void testRelativeResolution() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URL base = new URL(runtime, "https://example.test/a/b/c?old=1#base");
            assertEquals(
                    "https://example.test/a/d?x=1#z",
                    new URL(runtime, "../d?x=1#z", base).getHref(),
                    "relative path resolution");
            assertEquals(
                    "https://other.test/x",
                    new URL(runtime, "//Other.Test:443/x", base).getHref(),
                    "scheme-relative authority");
            assertEquals(
                    "https://example.test/a/b/c?q=2",
                    new URL(runtime, "?q=2", base).getHref(),
                    "query-only reference");
            assertEquals(
                    "https://example.test/a/b/c?old=1#new",
                    new URL(runtime, "#new", base).getHref(),
                    "fragment-only reference");
            assertEquals(
                    "https://example.test/a/b/c?old=1",
                    new URL(runtime, "", base).getHref(),
                    "empty reference drops base fragment");
            assertEquals(
                    "https://example.test/root/x",
                    new URL(runtime, "\\root\\x", base).getHref(),
                    "special backslashes are path separators");
        });
        fixture.runtime.close();
        pass();
    }

    private void testComponentGettersAndSetters() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URL url = new URL(runtime, "http://example.com:8080/a");
            url.setProtocol("https:");
            url.setUsername("a b");
            url.setPassword("p@ss");
            url.setHost("Example.ORG:443");
            url.setHostname("api.example.org");
            url.setPort("8443");
            url.setPathname("x/../y z");
            url.setSearch("?a=1 2");
            url.setHash("#h i");
            assertEquals(
                    "https://a%20b:p%40ss@api.example.org:8443/y%20z?a=1%202#h%20i",
                    url.getHref(),
                    "component setters");
            assertEquals("api.example.org:8443", url.getHost(), "host getter");
            assertEquals("api.example.org", url.getHostname(), "hostname getter");
            assertEquals("8443", url.getPort(), "port getter");
            assertEquals("1 2", url.getSearchParams().get("a"), "search setter updates params");

            String before = url.getHref();
            url.setHost("[::1]");
            url.setPort("70000");
            url.setProtocol("ftp:");
            assertEquals(before, url.getHref(), "invalid component setters are ignored");
        });
        fixture.runtime.close();
        pass();
    }

    private void testLiveSearchParamsAssociation() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URL url = new URL(runtime, "https://example.test/?a=1+2&x=%7E");
            URLSearchParams params = url.getSearchParams();
            assertEquals("1 2", params.get("a"), "query parsed as form data");
            params.append("b", "c d");
            assertEquals(
                    "https://example.test/?a=1+2&x=%7E&b=c+d",
                    url.getHref(),
                    "params mutation rewrites URL query");
            url.setSearch("?q=x%20y");
            assertSame(params, url.getSearchParams(), "query object identity retained");
            assertEquals("x y", params.get("q"), "search setter replaces params list");
            params.sort();
            assertEquals("?q=x+y", url.getSearch(), "params update uses form serialization");
            params.delete("q");
            assertEquals("", url.getSearch(), "empty params remove URL query");
            assertEquals("https://example.test/", url.getHref(), "empty query marker removed");
        });
        fixture.runtime.close();
        pass();
    }

    private void testEmptyQueryAndFragmentMarkers() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URL url = new URL(runtime, "https://example.test");
            assertEquals("https://example.test/", url.getHref(), "special URL root path");
            url.setSearch("?");
            url.setHash("#");
            assertEquals("", url.getSearch(), "empty query getter");
            assertEquals("", url.getHash(), "empty fragment getter");
            assertEquals("https://example.test/?#", url.getHref(), "empty markers serialized");
            url.getSearchParams().append("a", "b");
            assertEquals("https://example.test/?a=b#", url.getHref(), "params replace empty query");
            url.setPathname("/\ud83d\udca9?");
            assertEquals("/%F0%9F%92%A9%3F", url.getPathname(), "path scalar and question encoding");
        });
        fixture.runtime.close();
        pass();
    }

    private void testSelectedHostProfileAndParseHelpers() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            assertEquals(
                    "http://127.0.0.1/",
                    new URL(runtime, "http://127.0.0.1:80").getHref(),
                    "decimal IPv4 and default port");
            assertEquals(
                    "http://127.0.0.1/",
                    new URL(runtime, "http://127.0.0.1./").getHref(),
                    "trailing IPv4 dot canonicalized");
            assertThrows(
                    JsTypeError.class,
                    () -> new URL(runtime, "http://example.123/"),
                    "numeric-ending domain follows IPv4 failure");
            assertThrows(
                    JsTypeError.class,
                    () -> new URL(runtime, "http://127.00.0.1"),
                    "legacy IPv4 refused");
            assertThrows(
                    JsTypeError.class,
                    () -> new URL(runtime, "http://[::1]"),
                    "IPv6 refused until reached");
            assertThrows(
                    JsTypeError.class,
                    () -> new URL(runtime, "https://fa\u00df.example/"),
                    "IDNA refused until reached");
            assertThrows(
                    JsTypeError.class,
                    () -> new URL(runtime, "https://\u212a.example/"),
                    "Unicode case folding cannot bypass IDNA refusal");
            assertThrows(
                    JsTypeError.class,
                    () -> new URL(runtime, "ftp://example.test/"),
                    "non-http scheme refused");
            URL base = new URL(runtime, "https://example.test/base");
            assertTrue(URL.canParse(runtime, "/x", "https://example.test/base"), "canParse string base");
            assertTrue(URL.canParse(runtime, "/x", base), "canParse URL base");
            assertTrue(!URL.canParse(runtime, "/x"), "relative without base cannot parse");
            assertTrue(URL.parse(runtime, "http://[::1]") == null, "parse returns null on failure");
        });
        fixture.runtime.close();
        pass();
    }

    private void testHrefReplacementKeepsQueryObjectIdentity() throws Throwable {
        Fixture fixture = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            URL url = new URL(runtime, "https://a.test/?old=1");
            URLSearchParams params = url.getSearchParams();
            url.setHref("http://b.test:80/x?new=2#f");
            assertSame(params, url.getSearchParams(), "href setter retains query object");
            assertEquals("2", params.get("new"), "href setter refreshes query list");
            assertEquals(null, params.get("old"), "old query entries removed");
            assertEquals("http://b.test/x?new=2#f", url.getHref(), "href setter reparses URL");
        });
        fixture.runtime.close();
        pass();
    }

    private void testOwnerConfinementAndRuntimeIsolation() throws Throwable {
        Fixture fixture = new Fixture();
        URL[] url = new URL[1];
        runTurn(fixture.runtime, runtime -> url[0] = new URL(runtime, "https://example.test"));
        assertThrows(IllegalStateException.class, url[0]::getHref, "access outside active turn");

        Fixture other = new Fixture();
        runTurn(fixture.runtime, runtime -> {
            other.runtime.enterHostTurn();
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new URL(other.runtime, "x", url[0]),
                        "cross-runtime base refused");
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

    private static void assertEquals(String expected, String actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
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
