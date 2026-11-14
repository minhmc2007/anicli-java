package com.minhmc2077.aniclijava.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.data.AiringChecker;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.AnimeDetail;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.ui.adapter.DetailAdapter;
import com.minhmc2077.aniclijava.util.AiringAlarm;
import com.minhmc2077.aniclijava.util.AiringStatus;
import com.minhmc2077.aniclijava.util.Ui;

import java.util.ArrayList;
import java.util.List;

public final class AnimeDetailActivity extends BaseActivity {
    private static final String EXTRA_ID = "anime_id";
    private static final String EXTRA_TITLE = "anime_title";
    private static final String EXTRA_POSTER = "anime_poster";
    private static final String EXTRA_MODE = "mode";
    private static final int REQUEST_NOTIFICATIONS = 3301;

    private String animeId;
    private String animeTitle;
    private String animePoster;
    private String mode = "sub";

    private MaterialToolbar toolbar;
    private RecyclerView recycler;
    private DetailAdapter adapter;
    private Anime anime;
    private AnimeDetail detail;
    private final List<Episode> episodes = new ArrayList<>();

    private boolean episodesLoading;
    private boolean episodesLoaded;
    private boolean destroyed;

    public static void start(Context context, Anime anime, @Nullable String episodeNumber) {
        Intent intent = intent(context, anime);
        if (episodeNumber != null) {
            intent.putExtra("episode", episodeNumber);
        }
        context.startActivity(intent);
    }

    static Intent intent(Context context, Anime anime) {
        Intent intent = new Intent(context, AnimeDetailActivity.class);
        intent.putExtra(EXTRA_ID, anime.id);
        intent.putExtra(EXTRA_TITLE, anime.title);
        intent.putExtra(EXTRA_POSTER, anime.posterUrl);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setupEdgeToEdge(this);
        setContentView(R.layout.activity_anime_detail);

        toolbar = findViewById(R.id.toolbar);
        recycler = findViewById(R.id.recycler);
        Ui.applyTopInset(toolbar);
        Ui.applyBottomInset(recycler);

        toolbar.setNavigationOnClickListener(v -> finish());

        animeId = getIntent().getStringExtra(EXTRA_ID);
        animeTitle = getIntent().getStringExtra(EXTRA_TITLE);
        animePoster = getIntent().getStringExtra(EXTRA_POSTER);
        if (TextUtils.isEmpty(animeId)) {
            finish();
            return;
        }
        if (animeTitle == null) {
            animeTitle = "";
        }
        if (animePoster == null) {
            animePoster = "";
        }
        if (savedInstanceState != null) {
            mode = savedInstanceState.getString(EXTRA_MODE, "sub");
        }
        toolbar.setTitle(animeTitle);

        anime = new Anime(animeId, animeTitle, animePoster, "", 0, 0, 0, 0);

        int columns = Math.max(4, getResources().getConfiguration().screenWidthDp / 72);
        GridLayoutManager layoutManager = new GridLayoutManager(this, columns);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position == 0 ? columns : 1;
            }
        });
        recycler.setLayoutManager(layoutManager);

        adapter = new DetailAdapter(App.get().imageLoader(), new DetailAdapter.Listener() {
            @Override
            public void onPlay() {
                if (!episodes.isEmpty()) {
                    play(episodes.get(0));
                }
            }

            @Override
            public void onToggleFavourite() {
                boolean favourite = App.get().favourites().toggle(anime);
                if (adapter != null) {
                    adapter.setFavourite(favourite);
                }
                AiringAlarm.sync(AnimeDetailActivity.this);
                Ui.toast(AnimeDetailActivity.this, getString(favourite
                        ? R.string.favourite_added
                        : R.string.favourite_removed));
            }

            @Override
            public void onToggleNotify() {
                toggleAiringAlerts();
            }

            @Override
            public void onEpisode(Episode episode) {
                play(episode);
            }

            @Override
            public void onModeChanged(boolean dub) {
                mode = dub ? "dub" : "sub";
                if (adapter != null) {
                    adapter.setDub(dub);
                }
                updateVisibleEpisodes();
            }
        });
        recycler.setAdapter(adapter);
        adapter.setAnime(anime);
        adapter.setDub("dub".equals(mode));
        adapter.setFavourite(App.get().favourites().isFavourite(animeId));
        adapter.setNotifyEnabled(App.get().airing().isEnabled(animeId));

        loadDetail();
        loadEpisodes();
    }

    private void loadDetail() {
        App.get().async(
                () -> App.get().animeSource(animeId).detail(animeId, false),
                detail -> {
                    if (!destroyed && adapter != null) {
                        this.detail = detail;
                        adapter.setDetail(detail);
                        if (!TextUtils.isEmpty(detail.title)) {
                            toolbar.setTitle(detail.title);
                        }
                        updateVisibleEpisodes();
                    }
                },
                error -> {
                    // Detail metadata is optional; the episode list still works.
                }
        );
    }

    private void loadEpisodes() {
        if (episodesLoading || adapter == null) {
            return;
        }
        episodesLoading = true;
        adapter.setLoadingEpisodes(true, null);

        App.get().async(
                () -> App.get().animeSource(animeId).episodes(animeId, false),
                list -> {
                    episodesLoading = false;
                    episodesLoaded = true;
                    if (destroyed || adapter == null) {
                        return;
                    }
                    episodes.clear();
                    episodes.addAll(list);
                    updateVisibleEpisodes();
                },
                error -> {
                    episodesLoading = false;
                    if (destroyed || adapter == null) {
                        return;
                    }
                    adapter.setLoadingEpisodes(false, App.get().friendlyError(error));
                }
        );
    }

    private void play(Episode episode) {
        if (episode == null) {
            return;
        }
        PlayerActivity.start(this, anime, episode, mode);
    }

    private void updateVisibleEpisodes() {
        if (adapter == null || episodesLoading || !episodesLoaded) {
            return;
        }
        List<Episode> visible = episodes;
        if ("dub".equals(mode) && detail != null) {
            int dubbed = Math.min(detail.dubCount, episodes.size());
            visible = new ArrayList<>(episodes.subList(0, dubbed));
        }
        adapter.submitEpisodes(visible);
    }

    private void toggleAiringAlerts() {
        boolean enabled = !App.get().airing().isEnabled(animeId);
        if (enabled && detail != null && !AiringStatus.isAiring(detail.status)) {
            Ui.toast(this, getString(R.string.airing_only_for_airing));
            return;
        }
        App.get().airing().setEnabled(anime, enabled);
        if (adapter != null) {
            adapter.setNotifyEnabled(enabled);
        }
        if (enabled) {
            requestNotificationPermission();
            Ui.toast(this, getString(R.string.airing_enabled));
            AiringAlarm.sync(this);
            AiringChecker.refreshAsync(this, null);
        } else {
            AiringNotifications.cancel(this, animeId);
            Ui.toast(this, getString(R.string.airing_disabled));
            AiringAlarm.sync(this);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Ui.toast(this, getString(R.string.airing_permission_denied));
        }
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(EXTRA_MODE, mode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.setFavourite(App.get().favourites().isFavourite(animeId));
            adapter.setNotifyEnabled(App.get().airing().isEnabled(animeId));
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (recycler != null) {
            recycler.setAdapter(null);
        }
        adapter = null;
        super.onDestroy();
    }
}
