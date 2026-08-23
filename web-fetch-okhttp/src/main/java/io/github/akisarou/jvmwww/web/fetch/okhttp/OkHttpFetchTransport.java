package io.github.akisarou.jvmwww.web.fetch.okhttp;

import io.github.akisarou.jvmwww.web.fetch.FetchTransport;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCall;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportCallback;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest;
import io.github.akisarou.jvmwww.web.fetch.FetchTransportResponse;
import java.io.IOException;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** Buffered Fetch transport backed by an explicitly supplied OkHttp call factory. */
public final class OkHttpFetchTransport implements FetchTransport {
    private static final byte[] EMPTY_BODY = new byte[0];

    private final Call.Factory callFactory;

    public OkHttpFetchTransport(Call.Factory callFactory) {
        this.callFactory = Objects.requireNonNull(callFactory, "callFactory");
    }

    @Override
    public FetchTransportCall start(
            FetchTransportRequest request,
            FetchTransportCallback callback) {
        FetchTransportRequest checkedRequest = Objects.requireNonNull(request, "request");
        FetchTransportCallback checkedCallback = Objects.requireNonNull(callback, "callback");

        byte[] bodyBytes = checkedRequest.copyBody();
        RequestBody requestBody;
        if (bodyBytes != null) {
            requestBody = RequestBody.create(bodyBytes, null);
        } else if (requiresRequestBody(checkedRequest.getMethod())) {
            requestBody = RequestBody.EMPTY;
        } else {
            requestBody = null;
        }

        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .url(checkedRequest.getUrl())
                .method(checkedRequest.getMethod(), requestBody);
        for (int i = 0; i < checkedRequest.getHeaderCount(); i++) {
            requestBuilder.addHeader(
                    checkedRequest.getHeaderName(i),
                    checkedRequest.getHeaderValue(i));
        }

        Call call = Objects.requireNonNull(
                callFactory.newCall(requestBuilder.build()),
                "Call.Factory.newCall returned null");
        OkHttpFetchCall bridge = new OkHttpFetchCall(call, checkedCallback);
        call.enqueue(bridge);
        return bridge;
    }

    private static boolean requiresRequestBody(String method) {
        return "POST".equals(method)
                || "PUT".equals(method)
                || "PATCH".equals(method)
                || "PROPPATCH".equals(method)
                || "QUERY".equals(method)
                || "REPORT".equals(method);
    }

    private static boolean wasRedirected(okhttp3.Response response) {
        for (okhttp3.Response prior = response.priorResponse();
                prior != null;
                prior = prior.priorResponse()) {
            int status = prior.code();
            if (status == 300
                    || status == 301
                    || status == 302
                    || status == 303
                    || status == 307
                    || status == 308) {
                return true;
            }
        }
        return false;
    }

    /** One adapter object is both the OkHttp callback and Fetch cancellation handle. */
    private static final class OkHttpFetchCall implements Callback, FetchTransportCall {
        private final Call call;
        private final FetchTransportCallback callback;

        OkHttpFetchCall(Call call, FetchTransportCallback callback) {
            this.call = call;
            this.callback = callback;
        }

        @Override
        public void cancel() {
            call.cancel();
        }

        @Override
        public void onFailure(Call completedCall, IOException error) {
            if (completedCall != call) {
                callback.onFailure(new IllegalStateException(
                        "OkHttp callback delivered by another Call"));
                return;
            }
            callback.onFailure(Objects.requireNonNull(error, "error"));
        }

        @Override
        public void onResponse(Call completedCall, okhttp3.Response response) throws IOException {
            if (completedCall != call) {
                response.close();
                callback.onFailure(new IllegalStateException(
                        "OkHttp callback delivered by another Call"));
                return;
            }

            final FetchTransportResponse snapshot;
            try (okhttp3.Response ownedResponse = response) {
                int status = ownedResponse.code();
                if (status < 200 || status > 599) {
                    throw new IOException(
                            "Unsupported final HTTP status from OkHttp: " + status);
                }

                okhttp3.Headers responseHeaders = ownedResponse.headers();
                int headerCount = responseHeaders.size();
                String[] headerNames = new String[headerCount];
                String[] headerValues = new String[headerCount];
                for (int i = 0; i < headerCount; i++) {
                    headerNames[i] = responseHeaders.name(i);
                    headerValues[i] = responseHeaders.value(i);
                }

                ResponseBody responseBody = ownedResponse.body();
                byte[] body = responseBody == null ? EMPTY_BODY : responseBody.bytes();
                snapshot = new FetchTransportResponse(
                        ownedResponse.request().url().toString(),
                        status,
                        ownedResponse.message(),
                        headerNames,
                        headerValues,
                        body,
                        wasRedirected(ownedResponse));
            } catch (IOException error) {
                callback.onFailure(error);
                return;
            } catch (RuntimeException error) {
                callback.onFailure(error);
                return;
            }

            callback.onResponse(snapshot);
        }
    }
}
