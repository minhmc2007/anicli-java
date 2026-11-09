package com.minhmc2077.aniclijava.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.net.ImageLoader;
import com.minhmc2077.aniclijava.util.TimeAgo;

import java.util.ArrayList;
import java.util.List;

public final class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {
    public interface Listener {
        void onHistoryClick(HistoryEntry entry);

        void onRemove(HistoryEntry entry);
    }

    private final ImageLoader imageLoader;
    private final Listener listener;
    private final List<HistoryEntry> items = new ArrayList<>();

    public HistoryAdapter(ImageLoader imageLoader, Listener listener) {
        this.imageLoader = imageLoader;
        this.listener = listener;
    }

    public void submit(List<HistoryEntry> entries) {
        items.clear();
        if (entries != null) {
            items.addAll(entries);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        HistoryEntry entry = items.get(position);
        holder.title.setText(entry.title);
        holder.poster.setContentDescription(entry.title);
        imageLoader.load(entry.posterUrl, holder.poster);

        String episode = holder.itemView.getContext()
                .getString(R.string.history_episode, entry.episodeNumber);
        String ago = TimeAgo.format(entry.updatedAt);
        if (ago.isEmpty()) {
            holder.subtitle.setText(episode);
        } else {
            holder.subtitle.setText(holder.itemView.getContext()
                    .getString(R.string.history_watched, episode, ago));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onHistoryClick(entry);
            }
        });
        holder.remove.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRemove(entry);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView poster;
        final TextView title;
        final TextView subtitle;
        final ImageButton remove;

        Holder(@NonNull View itemView) {
            super(itemView);
            poster = itemView.findViewById(R.id.poster);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            remove = itemView.findViewById(R.id.remove);
        }
    }
}
