package com.minhmc2077.aniclijava.net;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.minhmc2077.aniclijava.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Very small image loader backed by Cronet + an in-memory LRU cache + a disk
 * cache. This keeps the app free of image-loading libraries.
 */
public final class ImageLoader {
    private static final long MAX_DISK_BYTES = 80L * 1024L * 1024L;
    private static final long TRIM_TO_BYTES = 55L * 1024L * 1024L;

    private final CronetHttp http;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory;
    private final File cacheDir;
    private final Map<String, String> headers;
    private int writesSinceTrim;

    public ImageLoader(Context context, CronetHttp http, Executor io) {
        this.http = http;
        this.io = io;
        this.cacheDir = new File(context.getCacheDir(), "posters");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheSize = Math.max(4 * 1024, maxMemory / 8);
        this.memory = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8");
        h.put("Referer", "https://hianime.at/");
        this.headers = Collections.unmodifiableMap(h);
    }

    public void load(String url, ImageView view) {
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            view.setImageResource(R.drawable.ic_image_placeholder);
            return;
        }

        view.setTag(url);
        Bitmap cached = memory.get(url);
        if (cached != null) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageBitmap(cached);
            return;
        }

        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImageResource(R.drawable.ic_image_placeholder);

        io.execute(() -> {
            Bitmap bitmap;
            try {
                bitmap = loadBitmap(url);
            } catch (Throwable error) {
                // A decode/cache failure must not kill the shared IO thread.
                return;
            }
            if (bitmap == null) {
                return;
            }
            memory.put(url, bitmap);
            main.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private Bitmap loadBitmap(String url) {
        File file = cacheFile(url);
        byte[] bytes = null;

        if (file.isFile() && file.length() > 0) {
            try {
                bytes = Files.readAllBytes(file.toPath());
            } catch (IOException ignored) {
                bytes = null;
            }
        }

        if (bytes == null) {
            try {
                CronetHttp.Response response = http.get(url, headers, false);
                if (response.isSuccessful() && response.body.length > 0) {
                    bytes = response.body;
                    writeFile(file, bytes);
                    if (++writesSinceTrim >= 40) {
                        writesSinceTrim = 0;
                        trimDiskCache();
                    }
                }
            } catch (IOException ignored) {
                return null;
            }
        }

        if (bytes == null) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private File cacheFile(String url) {
        return new File(cacheDir, sha1(url) + ".img");
    }

    private void writeFile(File file, byte[] bytes) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(bytes);
        } catch (IOException ignored) {
            // A failed cache write must never break image loading.
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void trimDiskCache() {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        if (total <= MAX_DISK_BYTES) {
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) {
            if (total <= TRIM_TO_BYTES) {
                break;
            }
            long length = file.length();
            if (file.delete()) {
                total -= length;
            }
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format(java.util.Locale.US, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
