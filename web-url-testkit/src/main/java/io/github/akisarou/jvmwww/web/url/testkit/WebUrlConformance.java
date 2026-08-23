package io.github.akisarou.jvmwww.web.url.testkit;

/** Runs every permanent conformance slice for web-url. */
public final class WebUrlConformance {
    private WebUrlConformance() {}

    public static void main(String[] args) throws Throwable {
        URLSearchParamsConformance.main(args);
        URLConformance.main(args);
    }
}
