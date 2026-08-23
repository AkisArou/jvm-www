package okhttp3;

import java.io.IOException;

/** Deterministic test-only subset of the OkHttp Callback API. */
public interface Callback {
    void onFailure(Call call, IOException error);
    void onResponse(Call call, Response response) throws IOException;
}
