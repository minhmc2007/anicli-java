package com.minhmc2077.aniclijava.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.ui.adapter.DetailAdapter;
import com.minhmc2077.aniclijava.util.Ui;

import java.util.ArrayList;
import java.util.List;

public final class AnimeDetailActivity extends AppCompatActivity {
    private static final String EXTRA_ID = "anime_id";
    private static final String EXTRA_TITLE = "anime_title";
    private static final String EXTRA_POSTER = "anime_poster";
    private static final String EXTRA_MODE = "mode";

    private String animeId;
    private String animeTitle;
    private String animePoster;
    private String mode = "sub";

    private MaterialToolbar toolbar;
    private RecyclerView recycler;
    private DetailAdapter adapter;
    private Anime anime;
    private final List<Episode> episodes = new ArrayList<>();

    private boolean episodesLoading;
    private boolean destroyed;

    public static void start(Context context, Anime anime, @Nullable String episodeNumber) {
        Intent intent = new Intent(context, AnimeDetailActivity.class);
        intent.putExtra(EXTRA_ID, anime.id);
        intent.putExtra(EXTRA_TITLE, anime.title);
        intent.putExtra(EXTRA_POSTER, anime.posterUrl);
        if (episodeNumber != null) {
            intent.putExtra("episode", episodeNumber);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setupEdgeToEdge(this);
        setContentView(R.layout.activity_anime_detail);
        Ui.applyVerticalInsets(findViewById(R.id.root));

        toolbar = findViewById(R.id.toolbar);
        recycler = findViewById(R.id.recycler);

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
                Ui.toast(AnimeDetailActivity.this, getString(favourite
                        ? R.string.favourite_added
                        : R.string.favourite_removed));
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
            }
        });
        recycler.setAdapter(adapter);
        adapter.setAnime(anime);
        adapter.setDub("dub".equals(mode));
        adapter.setFavourite(App.get().favourites().isFavourite(animeId));

        loadDetail();
        loadEpisodes();
    }

    private void loadDetail() {
        App.get().async(
                () -> App.get().repository().detail(animeId),
                detail -> {
                    if (!destroyed && adapter != null) {
                        adapter.setDetail(detail);
                        if (!TextUtils.isEmpty(detail.title)) {
                            toolbar.setTitle(detail.title);
                        }
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
                () -> App.get().repository().episodes(animeId),
                list -> {
                    episodesLoading = false;
                    if (destroyed || adapter == null) {
                        return;
                    }
                    episodes.clear();
                    episodes.addAll(list);
                    adapter.submitEpisodes(list);
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
