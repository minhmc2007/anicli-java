package com.minhmc2077.aniclijava.cache;

import android.content.Context;

import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.SimpleCache;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.util.EpisodeNumbers;
import com.minhmc2077.aniclijava.util.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CacheReport {
    private static final int[] COLORS = {
            0xFF1976D2, 0xFF388E3C, 0xFFD32F2F, 0xFF7B1FA2, 0xFFF57C00,
            0xFF00796B, 0xFFC2185B, 0xFF303F9F, 0xFF5D4037, 0xFF455A64
    };

    public static final class Item {
        public final String episodeNumber;
        public final String mode;
        public final long bytes;

        Item(String episodeNumber, String mode, long bytes) {
            this.episodeNumber = episodeNumber;
            this.mode = mode;
            this.bytes = bytes;
        }
    }

    public static final class Group {
        public final String animeId;
        public final String title;
        public final int color;
        public final long bytes;
        public final List<Item> items;

        Group(String animeId, String title, int color, long bytes, List<Item> items) {
            this.animeId = animeId;
            this.title = title;
            this.color = color;
            this.bytes = bytes;
            this.items = items;
        }
    }

    public final long usage;
    public final long limit;
    public final List<Group> groups;

    private CacheReport(long usage, long limit, List<Group> groups) {
        this.usage = usage;
        this.limit = limit;
        this.groups = groups;
    }

    public static CacheReport load(Context context) {
        SimpleCache cache = App.get().mediaCache();
        Map<String, HistoryEntry> history = new HashMap<>();
        for (HistoryEntry entry : App.get().history().getAll()) {
            if (!history.containsKey(entry.animeId)) {
                history.put(entry.animeId, entry);
            }
        }

        Map<String, GroupBuilder> builders = new LinkedHashMap<>();
        for (String key : cache.getKeys()) {
            long bytes = 0L;
            for (CacheSpan span : cache.getCachedSpans(key)) {
                if (span.isCached) {
                    bytes += span.length;
                }
            }
            if (bytes <= 0L) {
                continue;
            }
            String contentId = ContentCacheKeyFactory.contentId(key);
            String animeId = "";
            if (contentId != null) {
                int separator = contentId.indexOf('|');
                animeId = separator > 0 ? contentId.substring(0, separator) : contentId;
            }
            GroupBuilder builder = builders.get(animeId);
            if (builder == null) {
                builder = new GroupBuilder();
                builders.put(animeId, builder);
            }
            builder.bytes += bytes;
            String itemKey = contentId == null ? "" : contentId;
            Long previous = builder.items.get(itemKey);
            builder.items.put(itemKey, previous == null ? bytes : previous + bytes);
        }

        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, GroupBuilder> entry : builders.entrySet()) {
            String animeId = entry.getKey();
            GroupBuilder builder = entry.getValue();
            String title = null;
            HistoryEntry saved = history.get(animeId);
            if (saved != null && saved.title != null && !saved.title.isEmpty()) {
                title = saved.title;
            } else if (!animeId.isEmpty()) {
                title = animeId;
            } else {
                title = context.getString(R.string.cache_other);
            }
            int color = COLORS[Math.floorMod(animeId.hashCode(), COLORS.length)];

            List<Item> items = new ArrayList<>();
            for (Map.Entry<String, Long> itemEntry : builder.items.entrySet()) {
                String[] itemParts = itemEntry.getKey().isEmpty()
                        ? new String[0]
                        : itemEntry.getKey().split("\\|", 3);
                items.add(new Item(
                        itemParts.length > 1 ? itemParts[1] : "",
                        itemParts.length > 2 ? itemParts[2] : "sub",
                        itemEntry.getValue()));
            }
            Collections.sort(items, (first, second) -> {
                int compare = Double.compare(
                        EpisodeNumbers.value(first.episodeNumber),
                        EpisodeNumbers.value(second.episodeNumber));
                return compare != 0 ? compare
                        : first.episodeNumber.compareTo(second.episodeNumber);
            });
            groups.add(new Group(animeId, title, color, builder.bytes, items));
        }
        Collections.sort(groups, (first, second) -> Long.compare(second.bytes, first.bytes));

        return new CacheReport(cache.getCacheSpace(), Settings.cacheMaxBytes(context), groups);
    }

    private static final class GroupBuilder {
        long bytes;
        final Map<String, Long> items = new LinkedHashMap<>();
    }
}
