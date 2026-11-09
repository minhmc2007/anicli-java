package com.minhmc2077.aniclijava.net;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Tiny synchronous wrapper around Cronet. The scraping code runs on a background
 * executor, so blocking until the response is complete keeps the rest of the
 * app simple and readable.
 */
public final class CronetHttp {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String DEFAULT_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8";

    private final CronetEngine engine;
    private final Executor callbackExecutor;

    public CronetHttp(CronetEngine engine, Executor callbackExecutor) {
        this.engine = engine;
        this.callbackExecutor = callbackExecutor;
    }

    public static final class Response {
        public final int statusCode;
        public final byte[] body;

        Response(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public Response get(String url, Map<String, String> headers, boolean disableCache) throws IOException {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final int[] status = {0};
        final IOException[] failure = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        UrlRequest.Callback callback = new UrlRequest.Callback() {
            @Override
            public void onRedirectReceived(UrlRequest request, UrlResponseInfo info,
                                           String newLocationUrl) throws IOException {
                request.followRedirect();
            }

            @Override
            public void onResponseStarted(UrlRequest request, UrlResponseInfo info)
                    throws IOException {
                status[0] = info.getHttpStatusCode();
                request.read(ByteBuffer.allocateDirect(32 * 1024));
            }

            @Override
            public void onReadCompleted(UrlRequest request, UrlResponseInfo info,
                                        ByteBuffer buffer) throws IOException {
                buffer.flip();
                if (buffer.hasRemaining()) {
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    body.write(chunk);
                }
                buffer.clear();
                request.read(buffer);
            }

            @Override
            public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                latch.countDown();
            }

            @Override
            public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
                failure[0] = new IOException("Cronet request failed: " + error.getMessage(), error);
                latch.countDown();
            }

            @Override
            public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                failure[0] = new IOException("Cronet request canceled");
                latch.countDown();
            }
        };

        UrlRequest.Builder builder =
                engine.newUrlRequestBuilder(url, callback, callbackExecutor)
                        .setHttpMethod("GET")
                        .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_HIGH);

        if (disableCache) {
            builder.disableCache();
        }

        boolean hasAccept = false;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if ("accept".equals(entry.getKey().toLowerCase(Locale.US))) {
                    hasAccept = true;
                }
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        if (!hasAccept) {
            builder.addHeader("Accept", DEFAULT_ACCEPT);
        }
        builder.addHeader("Accept-Language", "en-US,en;q=0.9");
        builder.addHeader("User-Agent", USER_AGENT);

        UrlRequest request = builder.build();
        request.start();

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                request.cancel();
                throw new IOException("Request timed out");
            }
        } catch (InterruptedException interrupted) {
            request.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", interrupted);
        }

        if (failure[0] != null) {
            throw failure[0];
        }
        return new Response(status[0], body.toByteArray());
    }
}
