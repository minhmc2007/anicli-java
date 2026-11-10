package com.minhmc2077.aniclijava;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.minhmc2077.aniclijava.data.AniRepository;
import com.minhmc2077.aniclijava.data.FavouriteStore;
import com.minhmc2077.aniclijava.data.HistoryStore;
import com.minhmc2077.aniclijava.net.CronetHttp;
import com.minhmc2077.aniclijava.net.ImageLoader;
import com.minhmc2077.aniclijava.util.Settings;

import org.chromium.net.CronetEngine;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class App extends Application {
    private static final String TAG = "AniCliJava";
    private static App instance;

    private CronetEngine cronetEngine;
    private ExecutorService io;
    private ExecutorService cronetCallbacks;
    private Handler main;
    private CronetHttp http;
    private AniRepository repository;
    private ImageLoader imageLoader;
    private FavouriteStore favourites;
    private HistoryStore history;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Settings.applyNightMode(this);

        io = Executors.newFixedThreadPool(4);
        cronetCallbacks = Executors.newCachedThreadPool();
        main = new Handler(Looper.getMainLooper());

        File storage = new File(getCacheDir(), "cronet");
        if (!storage.exists()) {
            //noinspection ResultOfMethodCallIgnored
            storage.mkdirs();
        }

        cronetEngine = new CronetEngine.Builder(this)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                // setStoragePath must precede enableHttpCache(HTTP_CACHE_DISK).
                .setStoragePath(storage.getAbsolutePath())
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 64L * 1024L * 1024L)
                .setUserAgent(CronetHttp.USER_AGENT)
                .build();

        http = new CronetHttp(cronetEngine, cronetCallbacks);
        repository = new AniRepository(http);
        imageLoader = new ImageLoader(this, http, io);
        favourites = new FavouriteStore(this);
        history = new HistoryStore(this);
    }

    public CronetEngine cronetEngine() {
        return cronetEngine;
    }

    public CronetHttp http() {
        return http;
    }

    public ExecutorService io() {
        return io;
    }

    public ExecutorService cronetCallbacks() {
        return cronetCallbacks;
    }

    public AniRepository repository() {
        return repository;
    }

    public ImageLoader imageLoader() {
        return imageLoader;
    }

    public FavouriteStore favourites() {
        return favourites;
    }

    public HistoryStore history() {
        return history;
    }

    public <T> void async(Callable<T> task, Consumer<T> onSuccess, Consumer<Exception> onError) {
        io.execute(() -> {
            T result;
            try {
                result = task.call();
            } catch (Throwable error) {
                // Catch Throwable so a Cronet linkage/native error cannot kill
                // the pool thread and take down the process.
                Exception wrapped = error instanceof Exception
                        ? (Exception) error
                        : new RuntimeException(error);
                Log.e(TAG, "Background task failed", error);
                main.post(() -> deliver(onError, wrapped));
                return;
            }
            main.post(() -> deliver(onSuccess, result));
        });
    }

    private <T> void deliver(Consumer<T> callback, T value) {
        try {
            callback.accept(value);
        } catch (RuntimeException error) {
            // A callback touching a dead view must not crash the app.
            Log.e(TAG, "Callback failed", error);
        }
    }

    public String friendlyError(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("cloudflare")) {
            return getString(R.string.error_cloudflare);
        }
        if (lower.contains("no zokoanime") || lower.contains("no sources")) {
            return getString(R.string.error_no_sources);
        }
        if (lower.contains("http ")) {
            return message;
        }
        return getString(R.string.error_network);
    }

    public void log(String message) {
        Log.d(TAG, message);
    }
}
