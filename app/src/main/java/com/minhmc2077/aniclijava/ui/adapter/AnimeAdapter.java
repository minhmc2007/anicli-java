package com.minhmc2077.aniclijava.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.net.ImageLoader;

import java.util.ArrayList;
import java.util.List;

public final class AnimeAdapter extends RecyclerView.Adapter<AnimeAdapter.Holder> {
    public interface Listener {
        void onAnimeClick(Anime anime);
    }

    private final ImageLoader imageLoader;
    private final Listener listener;
    private final List<Anime> items = new ArrayList<>();

    public AnimeAdapter(ImageLoader imageLoader, Listener listener) {
        this.imageLoader = imageLoader;
        this.listener = listener;
    }

    public void submit(List<Anime> anime) {
        items.clear();
        if (anime != null) {
            items.addAll(anime);
        }
        notifyDataSetChanged();
    }

    public List<Anime> items() {
        return items;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_anime, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Anime anime = items.get(position);
        holder.title.setText(anime.title);
        holder.poster.setContentDescription(anime.title);

        if (anime.rank > 0) {
            holder.rank.setVisibility(View.VISIBLE);
            holder.rank.setText(String.format(java.util.Locale.US, "#%d", anime.rank));
        } else {
            holder.rank.setVisibility(View.GONE);
        }

        String meta = buildMeta(anime);
        if (meta.isEmpty()) {
            holder.meta.setVisibility(View.GONE);
        } else {
            holder.meta.setVisibility(View.VISIBLE);
            holder.meta.setText(meta);
        }

        imageLoader.load(anime.posterUrl, holder.poster);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAnimeClick(anime);
            }
        });
    }

    private String buildMeta(Anime anime) {
        if (anime.type != null && !anime.type.isEmpty()) {
            if (anime.episodes > 0) {
                return anime.type + " • " + anime.episodes + " eps";
            }
            return anime.type;
        }
        if (anime.rank > 0) {
            return "Trending now";
        }
        return "";
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView poster;
        final TextView title;
        final TextView meta;
        final TextView rank;

        Holder(@NonNull View itemView) {
            super(itemView);
            poster = itemView.findViewById(R.id.poster);
            title = itemView.findViewById(R.id.title);
            meta = itemView.findViewById(R.id.meta);
            rank = itemView.findViewById(R.id.rank);
        }
    }
}
