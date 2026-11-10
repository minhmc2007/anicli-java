package com.minhmc2077.aniclijava.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.imageview.ShapeableImageView;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.AnimeDetail;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.net.ImageLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_EPISODE = 1;

    public interface Listener {
        void onPlay();

        void onToggleFavourite();

        void onToggleNotify();

        void onEpisode(Episode episode);

        void onModeChanged(boolean dub);
    }

    private final ImageLoader imageLoader;
    private final Listener listener;
    private final List<Episode> episodes = new ArrayList<>();

    private Anime anime;
    private AnimeDetail detail;
    private boolean favourite;
    private boolean notifyEnabled;
    private boolean dub;
    private boolean loadingEpisodes;
    private String episodesError;
    private boolean bindingHeader;

    public DetailAdapter(ImageLoader imageLoader, Listener listener) {
        this.imageLoader = imageLoader;
        this.listener = listener;
    }

    public void setAnime(Anime anime) {
        this.anime = anime;
        notifyItemChanged(0);
    }

    public void setDetail(AnimeDetail detail) {
        this.detail = detail;
        notifyItemChanged(0);
    }

    public void setFavourite(boolean favourite) {
        this.favourite = favourite;
        notifyItemChanged(0);
    }

    public void setNotifyEnabled(boolean notifyEnabled) {
        this.notifyEnabled = notifyEnabled;
        notifyItemChanged(0);
    }

    public void setDub(boolean dub) {
        this.dub = dub;
        notifyItemChanged(0);
    }

    public void setLoadingEpisodes(boolean loading, String error) {
        this.loadingEpisodes = loading;
        this.episodesError = error;
        notifyItemChanged(0);
    }

    public void submitEpisodes(List<Episode> list) {
        episodes.clear();
        if (list != null) {
            episodes.addAll(list);
        }
        loadingEpisodes = false;
        episodesError = null;
        notifyDataSetChanged();
    }

    public List<Episode> episodes() {
        return episodes;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_EPISODE;
    }

    @Override
    public int getItemCount() {
        return episodes.size() + 1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_detail_header, parent, false);
            return new HeaderHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_episode, parent, false);
        return new EpisodeHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderHolder) {
            bindHeader((HeaderHolder) holder);
        } else {
            Episode episode = episodes.get(position - 1);
            EpisodeHolder episodeHolder = (EpisodeHolder) holder;
            episodeHolder.button.setText(episode.number);
            episodeHolder.button.setTag(episode);
        }
    }

    private void bindHeader(HeaderHolder holder) {
        String title = anime == null ? "" : anime.title;
        if (detail != null && !TextUtils.isEmpty(detail.title)) {
            title = detail.title;
        }
        holder.title.setText(title);
        holder.poster.setContentDescription(title);

        String poster = anime == null ? null : anime.posterUrl;
        if ((poster == null || poster.isEmpty()) && detail != null) {
            poster = detail.posterUrl;
        }
        imageLoader.load(poster, holder.poster);

        StringBuilder meta = new StringBuilder();
        if (anime != null && !TextUtils.isEmpty(anime.type)) {
            meta.append(anime.type);
        }
        int episodeCount = !episodes.isEmpty() ? episodes.size()
                : (anime == null ? 0 : anime.episodes);
        if (episodeCount > 0) {
            if (meta.length() > 0) {
                meta.append(" • ");
            }
            meta.append(episodeCount).append(" episodes");
        }
        if (detail != null && !TextUtils.isEmpty(detail.status)) {
            if (meta.length() > 0) {
                meta.append(" • ");
            }
            meta.append(detail.status);
        }
        if (meta.length() == 0) {
            holder.meta.setVisibility(View.GONE);
        } else {
            holder.meta.setVisibility(View.VISIBLE);
            holder.meta.setText(meta.toString());
        }

        if (detail != null && !TextUtils.isEmpty(detail.score)) {
            holder.score.setVisibility(View.VISIBLE);
            holder.score.setText(String.format(Locale.US, "★ %s", detail.score));
        } else {
            holder.score.setVisibility(View.GONE);
        }

        if (detail != null && !TextUtils.isEmpty(detail.genres)) {
            holder.genres.setVisibility(View.VISIBLE);
            holder.genres.setText(detail.genres);
        } else {
            holder.genres.setVisibility(View.GONE);
        }

        if (detail != null && !TextUtils.isEmpty(detail.description)) {
            holder.description.setVisibility(View.VISIBLE);
            holder.description.setText(detail.description);
        } else {
            holder.description.setVisibility(View.GONE);
        }

        holder.favourite.setText(favourite ? R.string.detail_favourited : R.string.detail_favourite);
        holder.favourite.setIconResource(favourite
                ? R.drawable.ic_favorite
                : R.drawable.ic_favorite_border);
        holder.notify.setAlpha(notifyEnabled ? 1f : 0.5f);
        holder.notify.setContentDescription(holder.itemView.getContext().getString(
                notifyEnabled ? R.string.airing_turn_off : R.string.airing_turn_on));
        holder.play.setEnabled(!episodes.isEmpty());

        bindingHeader = true;
        holder.modeGroup.check(dub ? R.id.mode_dub : R.id.mode_sub);
        bindingHeader = false;

        holder.episodesProgress.setVisibility(loadingEpisodes ? View.VISIBLE : View.GONE);
        if (episodesError != null && !episodesError.isEmpty()) {
            holder.episodesStatus.setVisibility(View.GONE);
            holder.episodesError.setVisibility(View.VISIBLE);
            holder.episodesError.setText(episodesError);
        } else {
            holder.episodesError.setVisibility(View.GONE);
            holder.episodesStatus.setVisibility(View.VISIBLE);
            if (loadingEpisodes) {
                holder.episodesStatus.setText(R.string.detail_loading_episodes);
            } else if (episodes.isEmpty()) {
                holder.episodesStatus.setText(R.string.detail_no_episodes);
            } else {
                holder.episodesStatus.setText(
                        holder.itemView.getContext().getString(R.string.detail_episode_count,
                                episodes.size()));
            }
        }
    }

    final class HeaderHolder extends RecyclerView.ViewHolder {
        final ShapeableImageView poster;
        final TextView title;
        final TextView meta;
        final TextView score;
        final TextView genres;
        final TextView description;
        final TextView episodesStatus;
        final TextView episodesError;
        final ProgressBar episodesProgress;
        final MaterialButton play;
        final MaterialButton favourite;
        final MaterialButton notify;
        final MaterialButtonToggleGroup modeGroup;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            poster = itemView.findViewById(R.id.detail_poster);
            title = itemView.findViewById(R.id.detail_title);
            meta = itemView.findViewById(R.id.detail_meta);
            score = itemView.findViewById(R.id.detail_score);
            genres = itemView.findViewById(R.id.detail_genres);
            description = itemView.findViewById(R.id.detail_description);
            episodesStatus = itemView.findViewById(R.id.episodes_status);
            episodesError = itemView.findViewById(R.id.episodes_error);
            episodesProgress = itemView.findViewById(R.id.episodes_progress);
            play = itemView.findViewById(R.id.detail_play);
            favourite = itemView.findViewById(R.id.detail_favourite);
            notify = itemView.findViewById(R.id.detail_notify);
            modeGroup = itemView.findViewById(R.id.mode_group);

            play.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPlay();
                }
            });
            favourite.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onToggleFavourite();
                }
            });
            notify.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onToggleNotify();
                }
            });
            modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked || bindingHeader || listener == null) {
                    return;
                }
                listener.onModeChanged(checkedId == R.id.mode_dub);
            });
        }
    }

    final class EpisodeHolder extends RecyclerView.ViewHolder {
        final MaterialButton button;

        EpisodeHolder(@NonNull View itemView) {
            super(itemView);
            button = itemView.findViewById(R.id.episode_button);
            button.setOnClickListener(v -> {
                Object tag = v.getTag();
                if (listener != null && tag instanceof Episode) {
                    listener.onEpisode((Episode) tag);
                }
            });
        }
    }
}
