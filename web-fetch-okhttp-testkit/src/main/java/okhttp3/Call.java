package okhttp3;

/** Deterministic test-only subset of the OkHttp Call API. */
public interface Call {
    Request request();
    void enqueue(Callback callback);
    void cancel();

    interface Factory {
        Call newCall(Request request);
    }
}
