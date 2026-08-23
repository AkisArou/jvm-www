package okhttp3;

import java.util.Objects;

/** Deterministic test-only immutable URL value. */
public final class HttpUrl {
    private final String value;

    HttpUrl(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
