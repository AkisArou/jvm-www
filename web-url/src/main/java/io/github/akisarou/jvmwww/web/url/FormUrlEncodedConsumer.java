package io.github.akisarou.jvmwww.web.url;

/** Immediate sink for decoded scalar form entries; implementations must not retain input bytes. */
public interface FormUrlEncodedConsumer {
    void acceptFormEntry(String name, String value);
}
