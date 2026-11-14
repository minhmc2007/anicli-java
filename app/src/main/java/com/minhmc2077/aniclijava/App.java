package com.minhmc2077.aniclijava;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.minhmc2077.aniclijava.cache.AdjustableLruCacheEvictor;
import com.minhmc2077.aniclijava.data.AiringStore;
import com.minhmc2077.aniclijava.data.AniRepository;
import com.minhmc2077.aniclijava.data.AniSkipApi;
import com.minhmc2077.aniclijava.data.AnimeSource;
import com.minhmc2077.aniclijava.data.FavouriteStore;
import com.minhmc2077.aniclijava.data.HistoryStore;
import com.minhmc2077.aniclijava.data.MkissaRepository;
import com.minhmc2077.aniclijava.data.OfflineStore;
import com.minhmc2077.aniclijava.data.SyncStore;
import com.minhmc2077.aniclijava.download.AniDownloadService;
import com.minhmc2077.aniclijava.download.PrefixedDownloaderFactory;
import com.minhmc2077.aniclijava.net.CronetHttp;
import com.minhmc2077.aniclijava.net.ImageLoader;
import com.minhmc2077.aniclijava.sync.AniListApi;
import com.minhmc2077.aniclijava.sync.MalApi;
import com.minhmc2077.aniclijava.sync.SyncManager;
import com.minhmc2077.aniclijava.util.AiringAlarm;
import com.minhmc2077.aniclijava.util.Settings;

import org.chromium.net.CronetEngine;

import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
    private MkissaRepository mkissa;
    private AniSkipApi aniSkip;
    private OfflineStore offline;
    private ImageLoader imageLoader;
    private FavouriteStore favourites;
    private HistoryStore history;
    private AiringStore airing;
    private SyncStore syncStore;
    private SyncManager sync;
    private DatabaseProvider databaseProvider;
    private AdjustableLruCacheEvictor cacheEvictor;
    private SimpleCache mediaCache;
    private CronetDataSource.Factory downloadDataSourceFactory;
    private DownloadManager downloadManager;
    private DownloadNotificationHelper downloadNotifications;

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
        mkissa = new MkissaRepository(http);
        aniSkip = new AniSkipApi(http);
        offline = new OfflineStore(this);
        imageLoader = new ImageLoader(this, http, io);
        favourites = new FavouriteStore(this);
        history = new HistoryStore(this);
        airing = new AiringStore(this);
        syncStore = new SyncStore(this);
        sync = new SyncManager(this, syncStore, new MalApi(http), new AniListApi(http));

        File media = new File(getCacheDir(), "media");
        if (!media.exists()) {
            //noinspection ResultOfMethodCallIgnored
            media.mkdirs();
        }
        databaseProvider = new StandaloneDatabaseProvider(this);
        cacheEvictor = new AdjustableLruCacheEvictor(Settings.cacheMaxBytes(this));
        mediaCache = new SimpleCache(media, cacheEvictor, databaseProvider);
        downloadDataSourceFactory = new CronetDataSource.Factory(cronetEngine, cronetCallbacks);
        setDownloadReferer(null);
        downloadManager = new DownloadManager(
                this,
                new DefaultDownloadIndex(databaseProvider),
                new PrefixedDownloaderFactory(mediaCache, downloadDataSourceFactory, io));
        downloadNotifications = new DownloadNotificationHelper(
                this, AniDownloadService.CHANNEL_ID);

        AiringAlarm.sync(this);
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

    public AnimeSource animeSource() {
        return Settings.SOURCE_MKISSA.equals(Settings.animeSource(this)) ? mkissa : repository;
    }

    public AnimeSource animeSource(String animeId) {
        return Settings.SOURCE_MKISSA.equals(Settings.sourceOf(animeId)) ? mkissa : repository;
    }

    public AniSkipApi aniSkip() {
        return aniSkip;
    }

    public OfflineStore offline() {
        return offline;
    }

    public SimpleCache mediaCache() {
        return mediaCache;
    }

    public long cacheUsage() {
        return mediaCache.getCacheSpace();
    }

    public void setCacheLimitBytes(long bytes) {
        cacheEvictor.setMaxBytes(mediaCache, bytes);
    }

    public DownloadManager downloadManager() {
        return downloadManager;
    }

    public DownloadNotificationHelper downloadNotifications() {
        return downloadNotifications;
    }

    public CronetDataSource.Factory downloadDataSourceFactory() {
        return downloadDataSourceFactory;
    }

    public void setDownloadReferer(String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", CronetHttp.USER_AGENT);
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
        }
        downloadDataSourceFactory.setDefaultRequestProperties(headers);
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

    public AiringStore airing() {
        return airing;
    }

    public SyncStore syncStore() {
        return syncStore;
    }

    public SyncManager sync() {
        return sync;
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
        if (lower.contains("captcha")) {
            return getString(R.string.error_mkissa_captcha);
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
