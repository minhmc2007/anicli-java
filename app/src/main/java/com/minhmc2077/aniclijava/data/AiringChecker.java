package com.minhmc2077.aniclijava.data;

import android.content.Context;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.AnimeDetail;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.ui.AiringNotifications;
import com.minhmc2077.aniclijava.util.AiringStatus;
import com.minhmc2077.aniclijava.util.EpisodeNumbers;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Compares the newest upstream episode with what was watched and raises a
 * notification for anime with airing alerts enabled.
 */
public final class AiringChecker {
    /**
     * The alarm repeats every ten minutes. UI-triggered refreshes are throttled
     * a little so switching tabs does not fetch the same lists repeatedly.
     */
    public static final long CHECK_INTERVAL_MS = 10L * 60L * 1000L;
    private static final long UI_REFRESH_TTL_MS = 60L * 1000L;
    private static final long STATUS_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private AiringChecker() {
    }

    /**
     * Fast, network-free lookup used by the favourites grid. The latest known
     * upstream episode comes from the last background check.
     */
    public static Set<String> badges() {
        Set<String> result = new HashSet<>();
        AiringStore airing = App.get().airing();
        HistoryStore history = App.get().history();
        for (Anime anime : App.get().favourites().getAll()) {
            HistoryEntry watched = history.latestForAnime(anime.id);
            if (watched == null || !airing.isAiring(anime.id)) {
                continue;
            }
            String latest = airing.latest(anime.id);
            if (!latest.isEmpty() && EpisodeNumbers.isNewer(latest, watched.episodeNumber)) {
                result.add(anime.id);
            }
        }
        return result;
    }

    /**
     * Fetches fresh episode lists for favourites and subscriptions, updates the
     * cached latest episode, and notifies for new episodes. Blocking; call from
     * a worker thread.
     */
    public static Set<String> refresh(Context context) {
        if (!RUNNING.compareAndSet(false, true)) {
            return badges();
        }
        try {
            AiringStore airing = App.get().airing();
            HistoryStore history = App.get().history();

            Map<String, Anime> tracked = new LinkedHashMap<>();
            for (Anime anime : App.get().favourites().getAll()) {
                // The badge only makes sense once something has been watched.
                if (history.latestForAnime(anime.id) != null) {
                    tracked.put(anime.id, anime);
                }
            }
            for (Anime anime : airing.subscriptions()) {
                tracked.put(anime.id, anime);
            }

            for (Anime anime : tracked.values()) {
                boolean airingNow;
                try {
                    airingNow = isAiringNow(airing, anime);
                } catch (IOException error) {
                    continue;
                }
                if (!airingNow) {
                    // Finished and unreleased titles never gain episodes.
                    continue;
                }
                List<Episode> episodes;
                try {
                    episodes = App.get().repository().episodes(anime.id, true);
                } catch (IOException error) {
                    // A single failing page must not stop the other checks.
                    continue;
                }
                String latest = EpisodeNumbers.latest(episodes);
                if (latest == null) {
                    continue;
                }
                airing.rememberLatest(anime.id, latest);
                if (airing.isEnabled(anime.id)) {
                    notifyIfNew(context, airing, history, anime, latest);
                }
            }

            airing.setLastRefresh(System.currentTimeMillis());
            return badges();
        } finally {
            RUNNING.set(false);
        }
    }

    public static void refreshAsync(Context context, Consumer<Set<String>> callback) {
        long elapsed = System.currentTimeMillis() - App.get().airing().lastRefresh();
        if (elapsed >= 0 && elapsed < UI_REFRESH_TTL_MS) {
            if (callback != null) {
                callback.accept(badges());
            }
            return;
        }
        App.get().async(
                () -> refresh(context),
                ids -> {
                    if (callback != null) {
                        callback.accept(ids);
                    }
                },
                error -> {
                    if (callback != null) {
                        callback.accept(badges());
                    }
                });
    }

    private static boolean isAiringNow(AiringStore airing, Anime anime) throws IOException {
        long now = System.currentTimeMillis();
        long checked = airing.airingCheckedAt(anime.id);
        if (checked <= 0L || now < checked || now - checked >= STATUS_TTL_MS) {
            AnimeDetail detail = App.get().repository().detail(anime.id, true);
            airing.setAiring(anime.id, AiringStatus.isAiring(detail.status));
        }
        return airing.isAiring(anime.id);
    }

    private static void notifyIfNew(Context context, AiringStore airing, HistoryStore history,
                                    Anime anime, String latest) {
        HistoryEntry watchedEntry = history.latestForAnime(anime.id);
        String watched = watchedEntry == null ? null : watchedEntry.episodeNumber;

        String notified = airing.lastNotified(anime.id);
        if (notified.isEmpty()) {
            if (watched == null) {
                // Nothing watched yet: start counting from the current episode
                // so enabling alerts does not announce the whole backlog.
                airing.setLastNotified(anime.id, latest);
                return;
            }
            notified = watched;
        }
        if (!EpisodeNumbers.isNewer(latest, notified)) {
            return;
        }
        if (watched != null && !EpisodeNumbers.isNewer(latest, watched)) {
            // Already watched up to the newest episode.
            airing.setLastNotified(anime.id, latest);
            return;
        }
        AiringNotifications.show(context, anime, latest);
        airing.setLastNotified(anime.id, latest);
    }
}
