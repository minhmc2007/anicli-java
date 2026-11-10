package com.minhmc2077.aniclijava.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.model.Quality;
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
    private View stateView;
    private ProgressBar stateProgress;
    private TextView stateText;
    private MaterialButton retryButton;
    private TextView seekIndicator;
    private GestureDetector seekGestureDetector;
    private final Runnable hideSeekHint = () -> {
        if (seekIndicator != null) {
            seekIndicator.setVisibility(View.GONE);
        }
    };

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
    private int generation;
    private boolean destroyed;

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
        playerView = findViewById(R.id.player_view);
        stateView = findViewById(R.id.state);
        stateProgress = findViewById(R.id.state_progress);
        stateText = findViewById(R.id.state_text);
        retryButton = findViewById(R.id.retry_button);
        seekIndicator = findViewById(R.id.seek_indicator);

        Ui.applyTopInset(topBar);
        setUpSeekGestures();

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
        retryButton.setOnClickListener(v -> resolveCurrent());

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setControllerAutoShow(true);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setShowSubtitleButton(true);

        dataSourceFactory = new CronetDataSource.Factory(
                App.get().cronetEngine(), App.get().cronetCallbacks());
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", CronetHttp.USER_AGENT);
        dataSourceFactory.setDefaultRequestProperties(headers);

        player = new ExoPlayer.Builder(this).build();
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
                } else if (playbackState == Player.STATE_ENDED) {
                    onPlaybackEnded();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
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
                },
                error -> {
                    if (!destroyed) {
                        updateNavigation();
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
        showLoading();

        App.get().async(
                () -> App.get().repository().resolveStream(requestedEpisode.id, requestedMode),
                info -> {
                    if (destroyed || request != generation) {
                        return;
                    }
                    stream = info;
                    selectedQuality = pickQuality(info);
                    updateQualityButton();
                    attachSource(info, selectedQuality);
                },
                error -> {
                    if (destroyed || request != generation) {
                        return;
                    }
                    showError(App.get().friendlyError(error));
                }
        );
    }

    private Quality pickQuality(StreamInfo info) {
        if (info.qualities.isEmpty()) {
            preferredHeight = 0;
            return new Quality("Auto", 0, info.masterUrl);
        }
        if (preferredHeight > 0) {
            for (Quality quality : info.qualities) {
                if (quality.height == preferredHeight) {
                    return quality;
                }
            }
        }
        Quality best = info.bestQuality();
        preferredHeight = best.height;
        return best;
    }

    private void attachSource(StreamInfo info, Quality quality) {
        if (player == null || info == null || quality == null) {
            return;
        }
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

        player.setMediaSource(buildMediaSource(info, quality));
        player.prepare();
        player.setPlayWhenReady(true);
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setPreferredTextLanguage("en")
                        .setSelectUndeterminedTextLanguage(true)
                        .build());

        App.get().history().upsert(animeId, animeTitle, posterUrl, episode.number,
                episode.id, mode, startMs);
    }

    private MediaSource buildMediaSource(StreamInfo info, Quality quality) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", CronetHttp.USER_AGENT);
        if (!TextUtils.isEmpty(info.referer)) {
            headers.put("Referer", info.referer);
        }
        dataSourceFactory.setDefaultRequestProperties(headers);

        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(quality.url)
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

        MediaSource video = new DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(item.build());

        if (!TextUtils.isEmpty(info.audioUrl) && !quality.url.equals(info.masterUrl)) {
            MediaSource audio = new HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(info.audioUrl));
            return new MergingMediaSource(video, audio);
        }
        return video;
    }

    private void toggleMode() {
        long position = player == null ? 0L : Math.max(0L, player.getCurrentPosition());
        pendingSeekMs = position;
        mode = "dub".equals(mode) ? "sub" : "dub";
        updateModeButton();
        resolveCurrent();
    }

    private void showQualityMenu() {
        if (stream == null || stream.qualities.isEmpty()) {
            return;
        }
        PopupMenu menu = new PopupMenu(this, qualityButton);
        for (int i = 0; i < stream.qualities.size(); i++) {
            menu.getMenu().add(0, i, i, stream.qualities.get(i).label);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (stream == null || item.getItemId() < 0
                    || item.getItemId() >= stream.qualities.size()) {
                return true;
            }
            Quality quality = stream.qualities.get(item.getItemId());
            if (selectedQuality != null && quality.url.equals(selectedQuality.url)) {
                return true;
            }
            long position = player == null ? 0L : Math.max(0L, player.getCurrentPosition());
            pendingSeekMs = position;
            preferredHeight = quality.height;
            selectedQuality = quality;
            updateQualityButton();
            attachSource(stream, quality);
            return true;
        });
        menu.show();
    }

    private void playEpisode(Episode next) {
        if (next == null) {
            return;
        }
        savePosition();
        episode = next;
        pendingSeekMs = -1L;
        updateEpisodeIndex();
        updateNavigation();
        updateTitle();
        resolveCurrent();
    }

    private void onPlaybackEnded() {
        // Finished: clear the resume point before advancing.
        App.get().history().updatePosition(animeId, episode.number, 0L);
        if (episodeIndex >= 0 && episodeIndex + 1 < episodes.size()) {
            playEpisode(episodes.get(episodeIndex + 1));
        } else {
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
        qualityButton.setText(selectedQuality == null
                ? getString(R.string.player_auto)
                : selectedQuality.label);
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

    private void setUpSeekGestures() {
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
            return false;
        });
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
        handler.removeCallbacks(saveProgress);
        handler.postDelayed(saveProgress, SAVE_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        savePosition();
        super.onPause();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(saveProgress);
        savePosition();
        if (player != null) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        generation++;
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
}
