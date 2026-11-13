package com.minhmc2077.aniclijava.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.cache.ContentCacheKeyFactory;
import com.minhmc2077.aniclijava.cache.PlaylistFreshDataSourceFactory;
import com.minhmc2077.aniclijava.download.EpisodeDownloads;
import com.minhmc2077.aniclijava.download.NextEpisodePrefetcher;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.model.Quality;
import com.minhmc2077.aniclijava.model.SkipTime;
import com.minhmc2077.aniclijava.model.StreamInfo;
import com.minhmc2077.aniclijava.net.CronetHttp;
import com.minhmc2077.aniclijava.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlayerActivity extends BaseActivity {
    private static final String EXTRA_ANIME_ID = "anime_id";
    private static final String EXTRA_TITLE = "anime_title";
    private static final String EXTRA_POSTER = "anime_poster";
    private static final String EXTRA_EPISODE_NUMBER = "episode_number";
    private static final String EXTRA_EPISODE_ID = "episode_id";
    private static final String EXTRA_MODE = "mode";
    private static final long SAVE_INTERVAL_MS = 5000L;
    private static final long SEEK_STEP_MS = 5000L;
    private static final long SEEK_HINT_MS = 700L;
    private static final long LEVEL_HINT_MS = 800L;
    private static final long SKIP_POLL_MS = 500L;
    private static final float BRIGHTNESS_MIN = 0.01f;
    private static final int MAX_BUFFER_BYTES = 64 * 1024 * 1024;
    private static final int SWIPE_NONE = 0;
    private static final int SWIPE_BRIGHTNESS = 1;
    private static final int SWIPE_VOLUME = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveProgress = new Runnable() {
        @Override
        public void run() {
            savePosition();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private PlayerView playerView;
    private ExoPlayer player;
    private CronetDataSource.Factory dataSourceFactory;

    private View topBar;
    private TextView titleView;
    private MaterialButton prevButton;
    private MaterialButton nextButton;
    private MaterialButton modeButton;
    private MaterialButton qualityButton;
    private MaterialButton skipButton;
    private MaterialButton downloadButton;
    private Download nextDownload;
    private final DownloadManager.Listener downloadListener = new DownloadManager.Listener() {
        @Override
        public void onDownloadChanged(DownloadManager downloadManager, Download download,
                                      @Nullable Exception finalException) {
            if (isNextEpisode(download)) {
                nextDownload = download;
                updateDownloadButton();
            }
        }

        @Override
        public void onDownloadRemoved(DownloadManager downloadManager, Download download) {
            if (isNextEpisode(download)) {
                nextDownload = null;
                updateDownloadButton();
            }
        }
    };
    private View stateView;
    private ProgressBar stateProgress;
    private TextView stateText;
    private MaterialButton retryButton;
    private TextView seekIndicator;
    private TextView levelIndicator;
    private GestureDetector seekGestureDetector;
    private final Runnable hideSeekHint = () -> {
        if (seekIndicator != null) {
            seekIndicator.setVisibility(View.GONE);
        }
    };
    private final Runnable hideLevelHint = () -> {
        if (levelIndicator != null) {
            levelIndicator.setVisibility(View.GONE);
        }
    };
    private final Runnable pollSkip = new Runnable() {
        @Override
        public void run() {
            if (!skipPolling) {
                return;
            }
            updateSkipButton();
            handler.postDelayed(this, SKIP_POLL_MS);
        }
    };

    private int touchSlop;
    private int swipeMode = SWIPE_NONE;
    private float swipeStartX;
    private float swipeStartY;
    private float swipeStartLevel;

    private String animeId;
    private String animeTitle;
    private String posterUrl;
    private String mode = "sub";
    private Episode episode;

    private final List<Episode> episodes = new ArrayList<>();
    private int episodeIndex = -1;
    private StreamInfo stream;
    private Quality selectedQuality;
    private int preferredHeight;
    private long pendingSeekMs = -1L;
    private long resumePositionMs = -1L;
    private String lastSyncedEpisodeId;
    private final List<SkipTime> skipTimes = new ArrayList<>();
    private SkipTime activeSkip;
    private String skipEpisodeId;
    private int skipRequest;
    private int skipButtonMode;
    private boolean skipPolling;
    private int generation;
    private boolean destroyed;
    private boolean playingOffline;
    private boolean retriedOnline;
    private boolean autoQuality = true;
    private boolean recoveredShortStream;
    private String autoDownloadKey;
    private NextEpisodePrefetcher prefetcher;

    public static void start(Context context, Anime anime, Episode episode, String mode) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_ANIME_ID, anime.id);
        intent.putExtra(EXTRA_TITLE, anime.title);
        intent.putExtra(EXTRA_POSTER, anime.posterUrl);
        intent.putExtra(EXTRA_EPISODE_NUMBER, episode.number);
        intent.putExtra(EXTRA_EPISODE_ID, episode.id);
        intent.putExtra(EXTRA_MODE, mode);
        context.startActivity(intent);
    }

    public static void start(Context context, HistoryEntry entry) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_ANIME_ID, entry.animeId);
        intent.putExtra(EXTRA_TITLE, entry.title);
        intent.putExtra(EXTRA_POSTER, entry.posterUrl);
        intent.putExtra(EXTRA_EPISODE_NUMBER, entry.episodeNumber);
        intent.putExtra(EXTRA_EPISODE_ID, entry.episodeId);
        intent.putExtra(EXTRA_MODE, entry.mode);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Black player surface: keep light system bar icons.
        Ui.setupEdgeToEdge(this, false);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        topBar = findViewById(R.id.top_bar);
        titleView = findViewById(R.id.title);
        prevButton = findViewById(R.id.prev_button);
        nextButton = findViewById(R.id.next_button);
        modeButton = findViewById(R.id.mode_button);
        qualityButton = findViewById(R.id.quality_button);
        skipButton = findViewById(R.id.skip_button);
        downloadButton = findViewById(R.id.download_button);
        skipButtonMode = com.minhmc2077.aniclijava.util.Settings.skipButtonMode(this);
        playerView = findViewById(R.id.player_view);
        stateView = findViewById(R.id.state);
        stateProgress = findViewById(R.id.state_progress);
        stateText = findViewById(R.id.state_text);
        retryButton = findViewById(R.id.retry_button);
        seekIndicator = findViewById(R.id.seek_indicator);
        levelIndicator = findViewById(R.id.level_indicator);

        Ui.applyTopInset(topBar);
        setUpGestures();

        animeId = getIntent().getStringExtra(EXTRA_ANIME_ID);
        animeTitle = getIntent().getStringExtra(EXTRA_TITLE);
        posterUrl = getIntent().getStringExtra(EXTRA_POSTER);
        String episodeNumber = getIntent().getStringExtra(EXTRA_EPISODE_NUMBER);
        String episodeId = getIntent().getStringExtra(EXTRA_EPISODE_ID);
        String requestedMode = getIntent().getStringExtra(EXTRA_MODE);

        if (TextUtils.isEmpty(animeId) || TextUtils.isEmpty(episodeId)) {
            finish();
            return;
        }
        if (animeTitle == null) {
            animeTitle = "";
        }
        if (posterUrl == null) {
            posterUrl = "";
        }
        if (episodeNumber == null) {
            episodeNumber = "";
        }
        mode = "dub".equalsIgnoreCase(requestedMode) ? "dub" : "sub";
        episode = new Episode(episodeNumber, episodeId);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        prevButton.setOnClickListener(v -> {
            if (episodeIndex > 0) {
                playEpisode(episodes.get(episodeIndex - 1));
            }
        });
        nextButton.setOnClickListener(v -> {
            if (episodeIndex >= 0 && episodeIndex + 1 < episodes.size()) {
                playEpisode(episodes.get(episodeIndex + 1));
            }
        });
        modeButton.setOnClickListener(v -> toggleMode());
        qualityButton.setOnClickListener(v -> showQualityMenu());
        skipButton.setOnClickListener(v -> skipCurrent());
        downloadButton.setOnClickListener(v -> downloadNextEpisode(true));
        retryButton.setOnClickListener(v -> {
            rememberPosition();
            resolveCurrent();
        });

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setControllerAutoShow(true);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setShowSubtitleButton(true);
        playerView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            @Override
            public void onVisibilityChanged(int visibility) {
                topBar.setVisibility(visibility);
            }
        });

        dataSourceFactory = new CronetDataSource.Factory(
                App.get().cronetEngine(), App.get().cronetCallbacks());
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", CronetHttp.USER_AGENT);
        dataSourceFactory.setDefaultRequestProperties(headers);

        int bufferMinutes = com.minhmc2077.aniclijava.util.Settings.bufferMinutes(this);
        int bufferMs = bufferMinutes == com.minhmc2077.aniclijava.util.Settings.BUFFER_UNLIMITED
                ? Integer.MAX_VALUE
                : bufferMinutes * 60_000;
        int minBufferMs = bufferMinutes == com.minhmc2077.aniclijava.util.Settings.BUFFER_UNLIMITED
                ? DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
                : bufferMs * 3 / 4;
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBufferMs, bufferMs,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setTargetBufferBytes(MAX_BUFFER_BYTES)
                .build();
        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    hideState();
                    if (resumePositionMs > 0) {
                        player.seekTo(resumePositionMs);
                    }
                    resumePositionMs = -1L;
                    loadSkipTimes();
                    maybeAutoDownloadNext();
                } else if (playbackState == Player.STATE_ENDED) {
                    stopSkipPolling();
                    hideSkipButton();
                    if (recoverShortEnding()) {
                        return;
                    }
                    onPlaybackEnded();
                }
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                updateQualityButton();
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                updateQualityButton();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (playingOffline && !retriedOnline) {
                    retriedOnline = true;
                    rememberPosition();
                    resolveCurrent();
                    return;
                }
                rememberPosition();
                showError(App.get().friendlyError(error));
            }
        });

        updateTitle();
        updateModeButton();
        updateQualityButton();
        updateNavigation();

        loadEpisodes();
        resolveCurrent();
    }

    private void loadEpisodes() {
        App.get().async(
                () -> App.get().repository().episodes(animeId),
                list -> {
                    if (destroyed) {
                        return;
                    }
                    episodes.clear();
                    episodes.addAll(list);
                    updateEpisodeIndex();
                    updateNavigation();
                    refreshNextDownload();
                    maybeAutoDownloadNext();
                },
                error -> {
                    if (!destroyed) {
                        updateNavigation();
                        refreshNextDownload();
                    }
                }
        );
    }

    private void resolveCurrent() {
        if (episode == null || TextUtils.isEmpty(episode.id)) {
            return;
        }
        if (player != null) {
            player.pause();
        }
        final int request = ++generation;
        final String requestedMode = mode;
        final Episode requestedEpisode = episode;
        final boolean preferOffline = !retriedOnline;
        showLoading();

        App.get().async(
                () -> resolveStream(requestedEpisode, requestedMode, preferOffline),
                resolved -> {
                    if (destroyed || request != generation) {
                        return;
                    }
                    watching(resolved);
                },
                error -> {
                    if (destroyed || request != generation) {
                        return;
                    }
                    if (!retriedOnline) {
                        StreamInfo cached = App.get().offline().stream(
                                animeId, requestedEpisode.number, requestedMode);
                        if (cached != null && !TextUtils.isEmpty(cached.masterUrl)) {
                            playingOffline = true;
                            applyStream(cached);
                            Ui.toast(this, getString(R.string.player_playing_offline));
                            return;
                        }
                    }
                    showError(App.get().friendlyError(error));
                }
        );
    }

    private ResolvedStream resolveStream(Episode target, String targetMode, boolean preferOffline)
            throws Exception {
        Download download = EpisodeDownloads.download(animeId, target.number, targetMode);
        boolean completed = download != null && download.state == Download.STATE_COMPLETED;
        if (preferOffline && completed) {
            StreamInfo cached = App.get().offline().stream(animeId, target.number, targetMode);
            if (cached != null && !TextUtils.isEmpty(cached.masterUrl)) {
                return new ResolvedStream(cached, true);
            }
        }
        StreamInfo info = App.get().repository().resolveStream(target.id, targetMode);
        if (download == null) {
            App.get().offline().putStream(animeId, target.number, targetMode, info);
        }
        return new ResolvedStream(info, false);
    }

    private void watching(ResolvedStream resolved) {
        stream = resolved.info;
        playingOffline = resolved.offline;
        applyStream(resolved.info);
    }

    private void applyStream(StreamInfo info) {
        stream = info;
        int malId = parseMalId(info.malId);
        if (malId > 0) {
            App.get().syncStore().putMalId(animeId, malId);
        }
        selectedQuality = pickQuality(info);
        updateQualityButton();
        attachSource(info, selectedQuality);
    }

    private Quality pickQuality(StreamInfo info) {
        if (info.qualities.isEmpty()) {
            preferredHeight = 0;
            return new Quality("Auto", 0, info.masterUrl);
        }
        if (preferredHeight > 0) {
            Quality higher = null;
            Quality lower = null;
            for (Quality quality : info.qualities) {
                if (quality.height == preferredHeight) {
                    return quality;
                }
                if (quality.height > preferredHeight
                        && (higher == null || quality.height < higher.height)) {
                    higher = quality;
                }
                if (quality.height < preferredHeight
                        && (lower == null || quality.height > lower.height)) {
                    lower = quality;
                }
            }
            // The previous height is gone (for example a truncated variant was
            // dropped): step up first, then fall back to the nearest lower one.
            Quality fallback = higher != null ? higher : lower;
            if (fallback != null) {
                preferredHeight = fallback.height;
                return fallback;
            }
        }
        Quality best = info.bestQuality();
        preferredHeight = best.height;
        return best;
    }

    private void attachSource(StreamInfo info, Quality quality) {
        attachSource(info, quality, null);
    }

    private void attachSource(StreamInfo info, Quality quality, String variantUrl) {
        if (player == null || info == null || quality == null) {
            return;
        }
        recoveredShortStream = false;
        skipTimes.clear();
        skipEpisodeId = null;
        skipRequest++;
        hideSkipButton();
        stopSkipPolling();
        showLoading();

        long startMs;
        if (pendingSeekMs >= 0) {
            startMs = pendingSeekMs;
            pendingSeekMs = -1L;
        } else {
            HistoryEntry history = App.get().history().find(animeId, episode.number);
            startMs = history == null ? 0L : Math.max(0L, history.positionMs);
        }
        resumePositionMs = startMs;

        player.setTrackSelectionParameters(selectionFor(quality));
        player.setMediaSource(buildMediaSource(info, variantUrl));
        player.prepare();
        player.setPlayWhenReady(true);

        App.get().history().upsert(animeId, animeTitle, posterUrl, episode.number,
                episode.id, mode, startMs);
        if (!episode.id.equals(lastSyncedEpisodeId)) {
            lastSyncedEpisodeId = episode.id;
            App.get().sync().pushEpisode(animeId, animeTitle, episode.number, false);
        }
        refreshNextDownload();
    }

    private void loadSkipTimes() {
        if (skipButtonMode == com.minhmc2077.aniclijava.util.Settings.SKIP_OFF || player == null || episode == null) {
            return;
        }
        long duration = player.getDuration();
        if (duration <= 0 || episode.id.equals(skipEpisodeId)) {
            return;
        }
        int malId = stream == null ? 0 : parseMalId(stream.malId);
        if (malId <= 0) {
            malId = App.get().syncStore().malId(animeId);
        }
        if (malId <= 0) {
            return;
        }

        final String episodeId = episode.id;
        final String number = episode.number;
        final int request = ++skipRequest;
        final int resolvedMalId = malId;
        skipEpisodeId = episodeId;

        App.get().async(
                () -> App.get().aniSkip().fetch(resolvedMalId, number, duration),
                times -> {
                    if (destroyed || request != skipRequest
                            || !episodeId.equals(skipEpisodeId)) {
                        return;
                    }
                    skipTimes.clear();
                    skipTimes.addAll(times);
                    if (!skipTimes.isEmpty()) {
                        startSkipPolling();
                    }
                    updateSkipButton();
                },
                error -> {
                    if (request == skipRequest) {
                        skipEpisodeId = null;
                    }
                }
        );
    }

    private void startSkipPolling() {
        if (skipPolling) {
            return;
        }
        skipPolling = true;
        handler.post(pollSkip);
    }

    private void stopSkipPolling() {
        skipPolling = false;
        handler.removeCallbacks(pollSkip);
    }

    private void updateSkipButton() {
        if (skipButton == null || player == null || skipButtonMode == com.minhmc2077.aniclijava.util.Settings.SKIP_OFF
                || player.getPlaybackState() != Player.STATE_READY) {
            hideSkipButton();
            return;
        }
        long lead = skipButtonMode == com.minhmc2077.aniclijava.util.Settings.SKIP_EARLY ? 2000L : 0L;
        long position = player.getCurrentPosition();
        SkipTime match = null;
        for (SkipTime skip : skipTimes) {
            if (skip.contains(position, lead)) {
                match = skip;
                break;
            }
        }
        if (match == null) {
            hideSkipButton();
            return;
        }
        activeSkip = match;
        skipButton.setText(match.isEnding()
                ? R.string.player_skip_ed
                : R.string.player_skip_op);
        skipButton.setVisibility(View.VISIBLE);
    }

    private void skipCurrent() {
        if (player == null || activeSkip == null) {
            return;
        }
        player.seekTo(activeSkip.endMs);
        hideSkipButton();
    }

    private void hideSkipButton() {
        activeSkip = null;
        if (skipButton != null) {
            skipButton.setVisibility(View.GONE);
        }
    }

    private static int parseMalId(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private TrackSelectionParameters selectionFor(Quality quality) {
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters()
                .buildUpon()
                .setPreferredTextLanguage("en")
                .setSelectUndeterminedTextLanguage(true);
        int minHeight = stream == null ? 0 : stream.minPlayableHeight();
        int maxHeight = quality != null && quality.height > 0
                ? quality.height
                : Integer.MAX_VALUE;
        if (minHeight > 0) {
            builder.setMinVideoSize(1, Math.min(minHeight, maxHeight));
        } else {
            builder.setMinVideoSize(0, 0);
        }
        builder.setMaxVideoSize(Integer.MAX_VALUE, maxHeight);
        return builder.build();
    }

    private MediaSource buildMediaSource(StreamInfo info, String variantUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", CronetHttp.USER_AGENT);
        if (!TextUtils.isEmpty(info.referer)) {
            headers.put("Referer", info.referer);
            Uri referer = Uri.parse(info.referer);
            if (referer.getScheme() != null && referer.getAuthority() != null) {
                headers.put("Origin", referer.getScheme() + "://" + referer.getAuthority());
            }
        }
        dataSourceFactory.setDefaultRequestProperties(headers);

        // Play the master so ExoPlayer can adapt between the healthy variants.
        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(TextUtils.isEmpty(variantUrl) ? info.masterUrl : variantUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8);
        if (!TextUtils.isEmpty(info.subtitleUrl)) {
            MediaItem.SubtitleConfiguration subtitle =
                    new MediaItem.SubtitleConfiguration.Builder(Uri.parse(info.subtitleUrl))
                            .setMimeType(MimeTypes.TEXT_VTT)
                            .setLanguage("en")
                            .setLabel("English")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build();
            item.setSubtitleConfigurations(Collections.singletonList(subtitle));
        }

        return new DefaultMediaSourceFactory(streamingSource()).createMediaSource(item.build());
    }

    private DataSource.Factory streamingSource() {
        return new PlaylistFreshDataSourceFactory(cacheSource(), dataSourceFactory,
                !playingOffline);
    }

    private CacheDataSource.Factory cacheSource() {
        String contentId = EpisodeDownloads.id(animeId, episode.number, mode);
        CacheDataSink.Factory sink = new CacheDataSink.Factory()
                .setCache(App.get().mediaCache());
        return new CacheDataSource.Factory()
                .setCache(App.get().mediaCache())
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setCacheKeyFactory(new ContentCacheKeyFactory(contentId))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setCacheWriteDataSinkFactory(sink);
    }

    private void toggleMode() {
        long position = player == null ? 0L : Math.max(0L, player.getCurrentPosition());
        cancelPrefetch();
        autoDownloadKey = null;
        pendingSeekMs = position;
        mode = "dub".equals(mode) ? "sub" : "dub";
        playingOffline = false;
        retriedOnline = false;
        updateModeButton();
        resolveCurrent();
    }

    private void showQualityMenu() {
        if (stream == null || stream.qualities.isEmpty()) {
            return;
        }
        PopupMenu menu = new PopupMenu(this, qualityButton);
        menu.getMenu().add(0, 0, 0, getString(R.string.player_auto));
        for (int i = 0; i < stream.qualities.size(); i++) {
            menu.getMenu().add(0, i + 1, i + 1, stream.qualities.get(i).label);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (stream == null || item.getItemId() < 0
                    || item.getItemId() > stream.qualities.size()) {
                return true;
            }
            if (item.getItemId() == 0) {
                autoQuality = true;
                preferredHeight = 0;
                selectedQuality = stream.bestQuality();
                updateQualityButton();
                if (player != null) {
                    player.setTrackSelectionParameters(selectionFor(selectedQuality));
                }
                return true;
            }
            Quality quality = stream.qualities.get(item.getItemId() - 1);
            if (!autoQuality && selectedQuality != null
                    && quality.url.equals(selectedQuality.url)) {
                return true;
            }
            autoQuality = false;
            preferredHeight = quality.height;
            selectedQuality = quality;
            updateQualityButton();
            if (player != null) {
                player.setTrackSelectionParameters(selectionFor(quality));
            }
            return true;
        });
        menu.show();
    }

    private Episode nextEpisode() {
        if (episodeIndex >= 0 && episodeIndex + 1 < episodes.size()) {
            return episodes.get(episodeIndex + 1);
        }
        return null;
    }

    private boolean isNextEpisode(Download download) {
        Episode next = nextEpisode();
        return download != null && next != null
                && EpisodeDownloads.id(animeId, next.number, mode).equals(download.request.id);
    }

    private void refreshNextDownload() {
        Episode next = nextEpisode();
        nextDownload = null;
        updateDownloadButton();
        if (next == null) {
            return;
        }
        final String expected = EpisodeDownloads.id(animeId, next.number, mode);
        App.get().async(
                () -> EpisodeDownloads.download(animeId, next.number, mode),
                download -> {
                    if (destroyed) {
                        return;
                    }
                    Episode current = nextEpisode();
                    if (current == null || !expected.equals(
                            EpisodeDownloads.id(animeId, current.number, mode))) {
                        return;
                    }
                    nextDownload = download;
                    updateDownloadButton();
                },
                error -> {
                }
        );
    }

    private void maybeAutoDownloadNext() {
        if (episode == null
                || !com.minhmc2077.aniclijava.util.Settings.autoDownloadNext(this)) {
            return;
        }
        if (nextEpisode() == null) {
            return;
        }
        String key = EpisodeDownloads.id(animeId, episode.number, mode);
        if (key.equals(autoDownloadKey)) {
            return;
        }
        cancelPrefetch();
        autoDownloadKey = key;
        downloadNextEpisode(false);
    }

    private void downloadNextEpisode(boolean announce) {
        final Episode current = episode;
        final String requestedMode = mode;
        if (current == null) {
            return;
        }
        Episode next = nextEpisode();
        if (next == null) {
            if (announce) {
                Ui.toast(this, getString(R.string.player_download_no_next));
            }
            return;
        }
        if (nextDownload != null) {
            if (nextDownload.state == Download.STATE_COMPLETED) {
                if (announce) {
                    Ui.toast(this, getString(R.string.player_download_done, next.number));
                }
                return;
            }
            if (nextDownload.state == Download.STATE_QUEUED
                    || nextDownload.state == Download.STATE_DOWNLOADING) {
                if (announce) {
                    Ui.toast(this, getString(R.string.player_download_queued, next.number));
                }
                return;
            }
        }
        downloadButton.setEnabled(false);
        if (announce) {
            Ui.toast(this, getString(R.string.player_download_preparing));
        }
        App.get().async(
                () -> EpisodeDownloads.resolveNext(animeId, current.number, current.id,
                        requestedMode),
                target -> {
                    if (destroyed) {
                        return;
                    }
                    if (target == null) {
                        refreshNextDownload();
                        if (announce) {
                            Ui.toast(this, getString(R.string.player_download_no_next));
                        }
                        return;
                    }
                    int minutes = com.minhmc2077.aniclijava.util.Settings
                            .bufferMinutes(this);
                    if (!announce && minutes
                            != com.minhmc2077.aniclijava.util.Settings.BUFFER_UNLIMITED) {
                        startPrefetch(target, requestedMode, minutes);
                        refreshNextDownload();
                        return;
                    }
                    EpisodeDownloads.enqueue(this, animeId, target.episode, requestedMode,
                            target.info, new EpisodeDownloads.Callback() {
                                @Override
                                public void onQueued() {
                                    if (destroyed) {
                                        return;
                                    }
                                    refreshNextDownload();
                                    if (announce) {
                                        Ui.toast(PlayerActivity.this,
                                                getString(R.string.player_download_queued,
                                                        target.episode.number));
                                    }
                                }

                                @Override
                                public void onError(Exception error) {
                                    if (destroyed) {
                                        return;
                                    }
                                    refreshNextDownload();
                                    if (announce) {
                                        Ui.toast(PlayerActivity.this,
                                                getString(R.string.player_download_failed));
                                    }
                                }
                            });
                },
                error -> {
                    if (destroyed) {
                        return;
                    }
                    refreshNextDownload();
                    if (announce) {
                        Ui.toast(this, getString(R.string.player_download_failed));
                    }
                }
        );
    }

    private void startPrefetch(EpisodeDownloads.Target target, String targetMode, int minutes) {
        cancelPrefetch();
        int height = selectedQuality == null ? 0 : selectedQuality.height;
        NextEpisodePrefetcher task = new NextEpisodePrefetcher(
                animeId, target.episode, targetMode, target.info, height, minutes);
        prefetcher = task;
        App.get().async(
                () -> {
                    task.run();
                    return null;
                },
                ignored -> clearPrefetcher(task),
                error -> clearPrefetcher(task)
        );
    }

    private void clearPrefetcher(NextEpisodePrefetcher task) {
        if (prefetcher == task) {
            prefetcher = null;
        }
    }

    private void cancelPrefetch() {
        NextEpisodePrefetcher task = prefetcher;
        prefetcher = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void rememberPosition() {
        if (player == null || stream == null || pendingSeekMs >= 0) {
            return;
        }
        long position = player.getCurrentPosition();
        if (position <= 0) {
            return;
        }
        pendingSeekMs = position;
        savePosition();
    }

    private void updateDownloadButton() {
        if (downloadButton == null) {
            return;
        }
        Episode next = nextEpisode();
        if (next == null) {
            downloadButton.setEnabled(false);
            downloadButton.setAlpha(0.4f);
            downloadButton.setContentDescription(getString(R.string.player_download_no_next));
            return;
        }
        if (nextDownload == null) {
            downloadButton.setEnabled(true);
            downloadButton.setAlpha(1f);
            downloadButton.setContentDescription(getString(R.string.player_download_next));
            return;
        }
        if (nextDownload.state == Download.STATE_COMPLETED) {
            downloadButton.setEnabled(false);
            downloadButton.setAlpha(0.5f);
            downloadButton.setContentDescription(
                    getString(R.string.player_download_done, next.number));
            return;
        }
        if (nextDownload.state == Download.STATE_QUEUED
                || nextDownload.state == Download.STATE_DOWNLOADING) {
            float progress = nextDownload.getPercentDownloaded();
            int percent = progress < 0 ? 0 : Math.min(100, Math.round(progress));
            downloadButton.setEnabled(false);
            downloadButton.setAlpha(0.6f);
            downloadButton.setContentDescription(
                    getString(R.string.player_download_progress, next.number, percent));
            return;
        }
        downloadButton.setEnabled(true);
        downloadButton.setAlpha(1f);
        downloadButton.setContentDescription(getString(R.string.player_download_next));
    }

    private void playEpisode(Episode next) {
        if (next == null) {
            return;
        }
        savePosition();
        cancelPrefetch();
        autoDownloadKey = null;
        episode = next;
        pendingSeekMs = -1L;
        playingOffline = false;
        retriedOnline = false;
        updateEpisodeIndex();
        updateNavigation();
        updateTitle();
        resolveCurrent();
    }

    private boolean recoverShortEnding() {
        if (recoveredShortStream || player == null || stream == null
                || stream.durationMs <= 0L || stream.qualities.isEmpty()) {
            return false;
        }
        long played = player.getDuration();
        if (played <= 0L || played >= stream.durationMs * 8L / 10L) {
            return false;
        }
        Quality quality = selectedQuality != null ? selectedQuality : stream.bestQuality();
        long position = Math.max(0L, player.getCurrentPosition());
        pendingSeekMs = Math.max(0L, position - 1_000L);
        Ui.toast(this, getString(R.string.player_recovering));
        attachSource(stream, quality, quality.url);
        recoveredShortStream = true;
        return true;
    }

    private void onPlaybackEnded() {
        App.get().history().updatePosition(animeId, episode.number, 0L);
        if (episodeIndex >= 0 && episodeIndex + 1 < episodes.size()) {
            playEpisode(episodes.get(episodeIndex + 1));
        } else {
            App.get().sync().pushEpisode(animeId, animeTitle, episode.number, true);
            showFinished();
        }
    }

    private void updateEpisodeIndex() {
        episodeIndex = -1;
        if (episode == null) {
            return;
        }
        for (int i = 0; i < episodes.size(); i++) {
            Episode candidate = episodes.get(i);
            if (candidate.id.equals(episode.id) || candidate.number.equals(episode.number)) {
                episodeIndex = i;
                return;
            }
        }
    }

    private void updateTitle() {
        titleView.setText(getString(R.string.player_episode_title, animeTitle, episode.number));
    }

    private void updateModeButton() {
        modeButton.setText("dub".equals(mode) ? R.string.player_mode_dub : R.string.player_mode_sub);
    }

    private void updateQualityButton() {
        if (autoQuality) {
            int height = selectedVideoHeight();
            qualityButton.setText(height > 0
                    ? getString(R.string.player_auto_actual, height)
                    : getString(R.string.player_auto));
            return;
        }
        qualityButton.setText(selectedQuality == null
                ? getString(R.string.player_auto)
                : selectedQuality.label);
    }

    private int selectedVideoHeight() {
        if (player == null) {
            return 0;
        }
        Format format = player.getVideoFormat();
        return format == null ? 0 : Math.max(0, format.height);
    }

    private void updateNavigation() {
        prevButton.setEnabled(episodeIndex > 0);
        nextButton.setEnabled(episodeIndex >= 0 && episodeIndex + 1 < episodes.size());
    }

    private void showLoading() {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.VISIBLE);
        stateText.setText(R.string.loading);
        retryButton.setVisibility(View.GONE);
    }

    private void showError(String message) {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateText.setText(message == null || message.isEmpty()
                ? getString(R.string.player_error_title)
                : message);
        retryButton.setVisibility(View.VISIBLE);
    }

    private void showFinished() {
        if (stateView == null) {
            return;
        }
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateText.setText(R.string.player_finished);
        retryButton.setVisibility(View.GONE);
    }

    private void hideState() {
        if (stateView != null) {
            stateView.setVisibility(View.GONE);
        }
    }

    private void setUpGestures() {
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        seekGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                boolean forward = e.getX() >= playerView.getWidth() / 2f;
                seekBy(forward ? SEEK_STEP_MS : -SEEK_STEP_MS);
                return true;
            }
        });
        playerView.setOnTouchListener((view, event) -> {
            seekGestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeMode = SWIPE_NONE;
                    swipeStartX = event.getX();
                    swipeStartY = event.getY();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (swipeMode == SWIPE_NONE) {
                        float dx = event.getX() - swipeStartX;
                        float dy = event.getY() - swipeStartY;
                        if (Math.abs(dy) <= touchSlop || Math.abs(dy) <= Math.abs(dx)) {
                            return false;
                        }
                        swipeMode = swipeStartX < view.getWidth() / 2f
                                ? SWIPE_BRIGHTNESS
                                : SWIPE_VOLUME;
                        swipeStartLevel = swipeMode == SWIPE_BRIGHTNESS
                                ? currentWindowBrightness()
                                : (player == null ? 1f : player.getVolume());
                    }
                    applySwipe(event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean adjusting = swipeMode != SWIPE_NONE;
                    swipeMode = SWIPE_NONE;
                    if (adjusting) {
                        handler.removeCallbacks(hideLevelHint);
                        handler.postDelayed(hideLevelHint, LEVEL_HINT_MS);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    private void applySwipe(float currentY) {
        float travel = (swipeStartY - currentY) / Math.max(1f, playerView.getHeight() * 0.7f);
        float level = clamp(swipeStartLevel + travel, 0f, 1f);
        if (swipeMode == SWIPE_BRIGHTNESS) {
            level = Math.max(BRIGHTNESS_MIN, level);
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = level;
            getWindow().setAttributes(params);
            showLevelHint(R.string.player_brightness, level);
        } else if (swipeMode == SWIPE_VOLUME) {
            if (player == null) {
                return;
            }
            // Player volume only affects this app's playback, so no audio
            // permission is needed and the device volume is left untouched.
            player.setVolume(level);
            showLevelHint(R.string.player_volume, level);
        }
    }

    private float currentWindowBrightness() {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (params.screenBrightness >= 0f) {
            return params.screenBrightness;
        }
        try {
            int system = Settings.System.getInt(
                    getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return clamp(system / 255f, BRIGHTNESS_MIN, 1f);
        } catch (Settings.SettingNotFoundException ignored) {
            return 0.5f;
        }
    }

    private void showLevelHint(int labelRes, float level) {
        if (levelIndicator == null) {
            return;
        }
        int percent = Math.round(clamp(level, 0f, 1f) * 100f);
        if (seekIndicator != null) {
            seekIndicator.setVisibility(View.GONE);
        }
        levelIndicator.setText(getString(R.string.player_level, getString(labelRes), percent));
        levelIndicator.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideLevelHint);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void seekBy(long deltaMs) {
        if (player == null) {
            return;
        }
        long duration = player.getDuration();
        long target = Math.max(0L, player.getCurrentPosition() + deltaMs);
        if (duration > 0 && target > duration) {
            target = duration;
        }
        player.seekTo(target);
        showSeekHint(deltaMs > 0);
    }

    private void showSeekHint(boolean forward) {
        if (seekIndicator == null) {
            return;
        }
        seekIndicator.setText(forward ? R.string.player_seek_forward : R.string.player_seek_back);
        if (levelIndicator != null) {
            levelIndicator.setVisibility(View.GONE);
        }
        seekIndicator.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSeekHint);
        handler.postDelayed(hideSeekHint, SEEK_HINT_MS);
    }

    private void savePosition() {
        if (player == null || episode == null || stream == null) {
            return;
        }
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        // Don't resume from the final 5 seconds.
        if (duration > 0 && position >= duration - 5000L) {
            position = 0L;
        }
        if (position < 0) {
            return;
        }
        App.get().history().updatePosition(animeId, episode.number, position);
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.get().downloadManager().addListener(downloadListener);
        refreshNextDownload();
        if (com.minhmc2077.aniclijava.util.Settings.autoDownloadNext(this)) {
            maybeAutoDownloadNext();
        } else {
            cancelPrefetch();
            autoDownloadKey = null;
        }
        handler.removeCallbacks(saveProgress);
        handler.postDelayed(saveProgress, SAVE_INTERVAL_MS);
        if (!skipTimes.isEmpty() && skipButtonMode != com.minhmc2077.aniclijava.util.Settings.SKIP_OFF) {
            startSkipPolling();
        }
    }

    @Override
    protected void onPause() {
        savePosition();
        super.onPause();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(saveProgress);
        stopSkipPolling();
        hideSkipButton();
        savePosition();
        App.get().downloadManager().removeListener(downloadListener);
        if (player != null) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        generation++;
        skipRequest++;
        cancelPrefetch();
        stopSkipPolling();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) {
            playerView.setPlayer(null);
        }
        super.onDestroy();
    }

    private static final class ResolvedStream {
        final StreamInfo info;
        final boolean offline;

        ResolvedStream(StreamInfo info, boolean offline) {
            this.info = info;
            this.offline = offline;
        }
    }
}
