package com.minhmc2077.aniclijava.net;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

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
        return send("GET", url, headers, null, null, disableCache);
    }

    public Response request(String method, String url, Map<String, String> headers,
                            String body, String contentType) throws IOException {
        return send(method, url, headers, body, contentType, true);
    }

    private Response send(String method, String url, Map<String, String> headers,
                          String body, String contentType, boolean disableCache)
            throws IOException {
        final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
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
                    responseBody.write(chunk);
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
                failure[0] = new IOException("Cronet request failed (" + url + "): "
                        + error.getMessage(), error);
                latch.countDown();
            }

            @Override
            public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                failure[0] = new IOException("Cronet request canceled");
                latch.countDown();
            }
        };

        UrlRequest request = requestBuilder(method, url, callback, headers, body, contentType,
                disableCache).build();
        request.start();

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                request.cancel();
                throw new IOException("Request timed out (" + url + ")");
            }
        } catch (InterruptedException interrupted) {
            request.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", interrupted);
        }

        if (failure[0] != null) {
            throw failure[0];
        }
        return new Response(status[0], responseBody.toByteArray());
    }

    public int download(String url, Map<String, String> headers, File target) throws IOException {
        return download(url, headers, target, null);
    }

    public int download(String url, Map<String, String> headers, File target,
                        ProgressListener listener) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        final FileOutputStream output = new FileOutputStream(target);
        final int[] status = {0};
        final long[] total = {-1L};
        final long[] received = {0L};
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
                total[0] = contentLength(info);
                if (listener != null) {
                    listener.onProgress(0L, total[0]);
                }
                request.read(ByteBuffer.allocateDirect(64 * 1024));
            }

            @Override
            public void onReadCompleted(UrlRequest request, UrlResponseInfo info,
                                        ByteBuffer buffer) throws IOException {
                buffer.flip();
                try {
                    if (buffer.hasRemaining()) {
                        byte[] chunk = new byte[buffer.remaining()];
                        buffer.get(chunk);
                        output.write(chunk);
                        received[0] += chunk.length;
                        if (listener != null) {
                            listener.onProgress(received[0], total[0]);
                        }
                    }
                } catch (IOException error) {
                    failure[0] = error;
                    latch.countDown();
                    request.cancel();
                    return;
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
                failure[0] = new IOException("Cronet request failed (" + url + "): "
                        + error.getMessage(), error);
                latch.countDown();
            }

            @Override
            public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                latch.countDown();
            }
        };

        UrlRequest request = requestBuilder("GET", url, callback, headers, null, null, true)
                .build();
        request.start();

        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                request.cancel();
                throw new IOException("Download timed out");
            }
        } catch (InterruptedException interrupted) {
            request.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", interrupted);
        } finally {
            output.close();
        }

        if (failure[0] != null) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw failure[0];
        }
        if (status[0] < 200 || status[0] >= 300) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("Download HTTP " + status[0]);
        }
        return status[0];
    }

    private static long contentLength(UrlResponseInfo info) {
        for (Map.Entry<String, List<String>> header : info.getAllHeaders().entrySet()) {
            if (!"content-length".equalsIgnoreCase(header.getKey()) || header.getValue().isEmpty()) {
                continue;
            }
            try {
                return Long.parseLong(header.getValue().get(0));
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
    }

    private UrlRequest.Builder requestBuilder(String method, String url,
                                              UrlRequest.Callback callback,
                                              Map<String, String> headers, String body,
                                              String contentType, boolean disableCache) {
        UrlRequest.Builder builder =
                engine.newUrlRequestBuilder(url, callback, callbackExecutor)
                        .setHttpMethod(method)
                        .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_HIGHEST);

        if (body != null) {
            builder.setUploadDataProvider(
                    new BytesUploadProvider(body.getBytes(StandardCharsets.UTF_8)),
                    callbackExecutor);
        }
        if (disableCache) {
            builder.disableCache();
        }

        boolean hasAccept = false;
        boolean hasContentType = false;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String name = entry.getKey().toLowerCase(Locale.US);
                if ("accept".equals(name)) {
                    hasAccept = true;
                } else if ("content-type".equals(name)) {
                    hasContentType = true;
                }
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        if (!hasAccept) {
            builder.addHeader("Accept", DEFAULT_ACCEPT);
        }
        if (!hasContentType && contentType != null) {
            builder.addHeader("Content-Type", contentType);
        }
        builder.addHeader("Accept-Language", "en-US,en;q=0.9");
        builder.addHeader("User-Agent", USER_AGENT);
        return builder;
    }

    private static final class BytesUploadProvider extends UploadDataProvider {
        private final byte[] data;
        private int offset;

        BytesUploadProvider(byte[] data) {
            this.data = data;
        }

        @Override
        public long getLength() {
            return data.length;
        }

        @Override
        public void read(UploadDataSink sink, ByteBuffer buffer) {
            int count = Math.min(buffer.remaining(), data.length - offset);
            buffer.put(data, offset, count);
            offset += count;
            sink.onReadSucceeded(offset >= data.length);
        }

        @Override
        public void rewind(UploadDataSink sink) {
            offset = 0;
            sink.onRewindSucceeded();
        }
    }
}
