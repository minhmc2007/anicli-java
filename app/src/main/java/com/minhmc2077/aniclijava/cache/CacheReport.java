package com.minhmc2077.aniclijava.cache;

import android.content.Context;

import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadIndex;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.util.EpisodeNumbers;
import com.minhmc2077.aniclijava.util.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CacheReport {
    private static final int[] COLORS = {
            0xFF1976D2, 0xFF388E3C, 0xFFD32F2F, 0xFF7B1FA2, 0xFFF57C00,
            0xFF00796B, 0xFFC2185B, 0xFF303F9F, 0xFF5D4037, 0xFF455A64
    };

    public static final class Item {
        public final String animeId;
        public final String episodeNumber;
        public final String mode;
        public final long bytes;
        final String contentId;
        final List<String> keys;

        Item(String animeId, String episodeNumber, String mode, long bytes,
             String contentId, List<String> keys) {
            this.animeId = animeId;
            this.episodeNumber = episodeNumber;
            this.mode = mode;
            this.bytes = bytes;
            this.contentId = contentId;
            this.keys = keys;
        }
    }

    public static final class Group {
        public final String animeId;
        public final String title;
        public final String posterUrl;
        public final int color;
        public final long bytes;
        public final List<Item> items;

        Group(String animeId, String title, String posterUrl, int color, long bytes,
              List<Item> items) {
            this.animeId = animeId;
            this.title = title;
            this.posterUrl = posterUrl;
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
        Map<String, Anime> favourites = new HashMap<>();
        for (Anime anime : App.get().favourites().getAll()) {
            favourites.put(anime.id, anime);
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
            ItemBuilder item = builder.items.get(itemKey);
            if (item == null) {
                item = new ItemBuilder();
                builder.items.put(itemKey, item);
            }
            item.bytes += bytes;
            item.keys.add(key);
        }

        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, GroupBuilder> entry : builders.entrySet()) {
            String animeId = entry.getKey();
            GroupBuilder builder = entry.getValue();
            HistoryEntry saved = history.get(animeId);
            Anime favourite = favourites.get(animeId);
            String title;
            if (saved != null && saved.title != null && !saved.title.isEmpty()) {
                title = saved.title;
            } else if (favourite != null && favourite.title != null && !favourite.title.isEmpty()) {
                title = favourite.title;
            } else if (!animeId.isEmpty()) {
                title = animeId;
            } else {
                title = context.getString(R.string.cache_other);
            }
            String posterUrl = saved == null ? null : saved.posterUrl;
            if ((posterUrl == null || posterUrl.isEmpty()) && favourite != null) {
                posterUrl = favourite.posterUrl;
            }
            int color = COLORS[Math.floorMod(animeId.hashCode(), COLORS.length)];

            List<Item> items = new ArrayList<>();
            for (Map.Entry<String, ItemBuilder> itemEntry : builder.items.entrySet()) {
                String contentId = itemEntry.getKey();
                String[] itemParts = contentId.isEmpty()
                        ? new String[0]
                        : contentId.split("\\|", 3);
                items.add(new Item(
                        itemParts.length > 0 ? itemParts[0] : "",
                        itemParts.length > 1 ? itemParts[1] : "",
                        itemParts.length > 2 ? itemParts[2] : "sub",
                        itemEntry.getValue().bytes,
                        contentId,
                        itemEntry.getValue().keys));
            }
            Collections.sort(items, (first, second) -> {
                int compare = Double.compare(
                        EpisodeNumbers.value(first.episodeNumber),
                        EpisodeNumbers.value(second.episodeNumber));
                return compare != 0 ? compare
                        : first.episodeNumber.compareTo(second.episodeNumber);
            });
            groups.add(new Group(animeId, title, posterUrl, color, builder.bytes, items));
        }
        Collections.sort(groups, (first, second) -> Long.compare(second.bytes, first.bytes));

        return new CacheReport(cache.getCacheSpace(), Settings.cacheMaxBytes(context), groups);
    }

    /** Removes the cached bytes and download record for a single episode. */
    public static void deleteItem(Item item) {
        removeKeys(item.keys);
        if (!item.contentId.isEmpty()) {
            removeDownloadIfExists(item.contentId);
            removeOfflineMetadata(item.contentId);
        }
    }

    /** Removes every cached episode, download record and saved stream for one anime. */
    public static void deleteGroup(Group group) {
        Set<String> contentIds = new LinkedHashSet<>();
        for (Item item : group.items) {
            removeKeys(item.keys);
            if (!item.contentId.isEmpty()) {
                contentIds.add(item.contentId);
            }
        }
        if (!group.animeId.isEmpty()) {
            String prefix = group.animeId + "|";
            try (DownloadCursor cursor = App.get().downloadManager()
                    .getDownloadIndex().getDownloads()) {
                while (cursor.moveToNext()) {
                    String id = cursor.getDownload().request.id;
                    if (id.startsWith(prefix)) {
                        contentIds.add(id);
                    }
                }
            } catch (Exception ignored) {
                // A broken download index must not block removing cached bytes.
            }
        }
        for (String contentId : contentIds) {
            removeDownloadIfExists(contentId);
            removeOfflineMetadata(contentId);
        }
    }

    private static void removeKeys(List<String> keys) {
        SimpleCache cache = App.get().mediaCache();
        for (String key : keys) {
            try {
                cache.removeResource(key);
            } catch (RuntimeException ignored) {
                // Never let a released cache crash the page.
            }
        }
    }

    private static void removeDownloadIfExists(String contentId) {
        try {
            DownloadIndex index = App.get().downloadManager().getDownloadIndex();
            Download download = index.getDownload(contentId);
            if (download != null) {
                App.get().downloadManager().removeDownload(contentId);
            }
        } catch (Exception ignored) {
        }
    }

    private static void removeOfflineMetadata(String contentId) {
        String[] parts = contentId.split("\\|", 3);
        if (parts.length == 3) {
            App.get().offline().removeStream(parts[0], parts[1], parts[2]);
        }
    }

    private static final class GroupBuilder {
        long bytes;
        final Map<String, ItemBuilder> items = new LinkedHashMap<>();
    }

    private static final class ItemBuilder {
        long bytes;
        final List<String> keys = new ArrayList<>();
    }
}
