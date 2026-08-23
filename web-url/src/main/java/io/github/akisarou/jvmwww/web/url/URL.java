package io.github.akisarou.jvmwww.web.url;

import io.github.akisarou.jvmwww.runtime.JsTypeError;
import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import java.util.Locale;
import java.util.Objects;

/** Owner-confined URL object for the selected special http/https WHATWG profile. */
public final class URL implements URLSearchParamsUpdateTarget {
    private final RuntimeInstance runtime;
    private final URLSearchParams searchParams;
    private UrlRecord url;

    public URL(RuntimeInstance runtime, String input) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        UrlRuntimeChecks.assertLanguageExecution(runtime);
        this.url = UrlParser.parse(runtime, Objects.requireNonNull(input, "input"), null);
        this.searchParams = new URLSearchParams(
                runtime,
                url.query == null ? "" : url.query,
                this);
    }

    public URL(RuntimeInstance runtime, String input, String base) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        UrlRuntimeChecks.assertLanguageExecution(runtime);
        UrlRecord parsedBase = UrlParser.parse(
                runtime,
                Objects.requireNonNull(base, "base"),
                null);
        this.url = UrlParser.parse(
                runtime,
                Objects.requireNonNull(input, "input"),
                parsedBase);
        this.searchParams = new URLSearchParams(
                runtime,
                url.query == null ? "" : url.query,
                this);
    }

    public URL(RuntimeInstance runtime, String input, URL base) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        UrlRuntimeChecks.assertLanguageExecution(runtime);
        URL checkedBase = Objects.requireNonNull(base, "base");
        checkedBase.assertAccess();
        if (checkedBase.runtime != runtime) {
            throw new IllegalArgumentException("Base URL belongs to another RuntimeInstance");
        }
        this.url = UrlParser.parse(
                runtime,
                Objects.requireNonNull(input, "input"),
                new UrlRecord(checkedBase.url));
        this.searchParams = new URLSearchParams(
                runtime,
                url.query == null ? "" : url.query,
                this);
    }

    public static URL parse(RuntimeInstance runtime, String input) {
        try {
            return new URL(runtime, input);
        } catch (JsTypeError error) {
            return null;
        }
    }

    public static URL parse(RuntimeInstance runtime, String input, String base) {
        try {
            return new URL(runtime, input, base);
        } catch (JsTypeError error) {
            return null;
        }
    }

    public static URL parse(RuntimeInstance runtime, String input, URL base) {
        try {
            return new URL(runtime, input, base);
        } catch (JsTypeError error) {
            return null;
        }
    }

    public static boolean canParse(RuntimeInstance runtime, String input) {
        Objects.requireNonNull(runtime, "runtime");
        UrlRuntimeChecks.assertLanguageExecution(runtime);
        try {
            UrlParser.parse(runtime, Objects.requireNonNull(input, "input"), null);
            return true;
        } catch (JsTypeError error) {
            return false;
        }
    }

    public static boolean canParse(RuntimeInstance runtime, String input, String base) {
        Objects.requireNonNull(runtime, "runtime");
        UrlRuntimeChecks.assertLanguageExecution(runtime);
        try {
            UrlRecord parsedBase = UrlParser.parse(
                    runtime,
                    Objects.requireNonNull(base, "base"),
                    null);
            UrlParser.parse(runtime, Objects.requireNonNull(input, "input"), parsedBase);
            return true;
        } catch (JsTypeError error) {
            return false;
        }
    }

    public static boolean canParse(RuntimeInstance runtime, String input, URL base) {
        Objects.requireNonNull(runtime, "runtime");
        UrlRuntimeChecks.assertLanguageExecution(runtime);
        URL checkedBase = Objects.requireNonNull(base, "base");
        checkedBase.assertAccess();
        if (checkedBase.runtime != runtime) {
            throw new IllegalArgumentException("Base URL belongs to another RuntimeInstance");
        }
        try {
            UrlParser.parse(
                    runtime,
                    Objects.requireNonNull(input, "input"),
                    new UrlRecord(checkedBase.url));
            return true;
        } catch (JsTypeError error) {
            return false;
        }
    }

    public RuntimeInstance getRuntime() {
        return runtime;
    }

    public String getHref() {
        assertAccess();
        return UrlParser.serialize(url);
    }

    public void setHref(String value) {
        assertAccess();
        url = UrlParser.parse(runtime, Objects.requireNonNull(value, "value"), null);
        searchParams.replaceFromQuery(url.query == null ? "" : url.query);
    }

    public String getOrigin() {
        assertAccess();
        return UrlParser.serializeOrigin(url);
    }

    public String getProtocol() {
        assertAccess();
        return url.scheme + ':';
    }

    /** Invalid or unreached schemes leave the URL unchanged, matching URL setter behavior. */
    public void setProtocol(String value) {
        assertAccess();
        String checked = UrlScalar.fromString(value, "protocol");
        if (checked.endsWith(":")) checked = checked.substring(0, checked.length() - 1);
        String scheme = checked.toLowerCase(Locale.ROOT);
        try {
            UrlHostParser.requireSupportedScheme(scheme);
        } catch (JsTypeError error) {
            return;
        }
        url.scheme = scheme;
        if (url.port == UrlHostParser.defaultPort(scheme)) url.port = -1;
    }

    public String getUsername() {
        assertAccess();
        return url.username;
    }

    public void setUsername(String value) {
        assertAccess();
        url.username = UrlPercentCodec.encode(
                runtime,
                Objects.requireNonNull(value, "value"),
                UrlPercentCodec.USERINFO);
    }

    public String getPassword() {
        assertAccess();
        return url.password;
    }

    public void setPassword(String value) {
        assertAccess();
        url.password = UrlPercentCodec.encode(
                runtime,
                Objects.requireNonNull(value, "value"),
                UrlPercentCodec.USERINFO);
    }

    public String getHost() {
        assertAccess();
        return url.port < 0 ? url.host : url.host + ':' + url.port;
    }

    /** Invalid host setter input is ignored rather than partially mutating the URL. */
    public void setHost(String value) {
        assertAccess();
        try {
            UrlHostParser.HostPort parsed = UrlHostParser.parseHostPort(
                    Objects.requireNonNull(value, "value"),
                    url.scheme,
                    true);
            url.host = parsed.host;
            if (parsed.portSpecified) url.port = parsed.port;
        } catch (JsTypeError error) {
            // URL component setters ignore parse failure.
        }
    }

    public String getHostname() {
        assertAccess();
        return url.host;
    }

    /** Invalid hostname setter input is ignored rather than partially mutating the URL. */
    public void setHostname(String value) {
        assertAccess();
        try {
            url.host = UrlHostParser.parseHostPort(
                    Objects.requireNonNull(value, "value"),
                    url.scheme,
                    false).host;
        } catch (JsTypeError error) {
            // URL component setters ignore parse failure.
        }
    }

    public String getPort() {
        assertAccess();
        return url.port < 0 ? "" : Integer.toString(url.port);
    }

    /** Invalid port setter input is ignored rather than partially mutating the URL. */
    public void setPort(String value) {
        assertAccess();
        String checked = Objects.requireNonNull(value, "value");
        if (checked.isEmpty()) {
            url.port = -1;
            return;
        }
        try {
            int parsed = UrlHostParser.parsePort(checked);
            url.port = parsed == UrlHostParser.defaultPort(url.scheme) ? -1 : parsed;
        } catch (JsTypeError error) {
            // URL component setters ignore parse failure.
        }
    }

    public String getPathname() {
        assertAccess();
        return UrlParser.serializePath(url);
    }

    public void setPathname(String value) {
        assertAccess();
        UrlPath.replace(runtime, url, Objects.requireNonNull(value, "value"));
    }

    public String getSearch() {
        assertAccess();
        return url.query == null || url.query.isEmpty() ? "" : '?' + url.query;
    }

    public void setSearch(String value) {
        assertAccess();
        String checked = UrlScalar.fromString(value, "search");
        if (checked.isEmpty()) {
            url.query = null;
            searchParams.replaceFromQuery("");
            return;
        }
        if (checked.charAt(0) == '?') checked = checked.substring(1);
        url.query = UrlParser.encodeQuery(runtime, checked);
        searchParams.replaceFromQuery(url.query);
    }

    public URLSearchParams getSearchParams() {
        assertAccess();
        return searchParams;
    }

    public String getHash() {
        assertAccess();
        return url.fragment == null || url.fragment.isEmpty() ? "" : '#' + url.fragment;
    }

    public void setHash(String value) {
        assertAccess();
        String checked = UrlScalar.fromString(value, "hash");
        if (checked.isEmpty()) {
            url.fragment = null;
            return;
        }
        if (checked.charAt(0) == '#') checked = checked.substring(1);
        url.fragment = UrlParser.encodeFragment(runtime, checked);
    }

    public String toJSON() {
        return getHref();
    }

    @Override
    public String toString() {
        return getHref();
    }

    @Override
    public void updateFromSearchParams(URLSearchParams params) {
        assertAccess();
        if (params != searchParams) {
            throw new IllegalArgumentException("URL updated by another URLSearchParams object");
        }
        String serialized = params.serialize();
        url.query = serialized.isEmpty() ? null : serialized;
    }

    private void assertAccess() {
        UrlRuntimeChecks.assertLanguageExecution(runtime);
    }
}
