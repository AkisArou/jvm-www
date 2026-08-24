package okhttp3;

import java.util.ArrayList;
import java.util.List;

public final class Request {
    private final String url;
    private final Headers headers;

    Request(String url, Headers headers) {
        this.url = url;
        this.headers = headers;
    }

    public String url() { return url; }
    public Headers headers() { return headers; }

    public static final class Builder {
        private String url;
        private final List<String> names = new ArrayList<String>();
        private final List<String> values = new ArrayList<String>();

        public Builder url(String value) { url = value; return this; }
        public Builder addHeader(String name, String value) {
            names.add(name);
            values.add(value);
            return this;
        }
        public Request build() {
            return new Request(
                    url,
                    new Headers(
                            names.toArray(new String[names.size()]),
                            values.toArray(new String[values.size()])));
        }
    }
}
