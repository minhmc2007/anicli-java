package com.minhmc2077.aniclijava.sync;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.data.SyncStore;
import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.util.EpisodeNumbers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class SyncManager {
    private static final long AUTO_SYNC_INTERVAL = 6L * 60L * 60L * 1000L;
    private static final long TOKEN_REFRESH_WINDOW = 60L * 1000L;

    private final App app;
    private final SyncStore store;
    private final MalApi mal;
    private final AniListApi aniList;

    public SyncManager(App app, SyncStore store, MalApi mal, AniListApi aniList) {
        this.app = app;
        this.store = store;
        this.mal = mal;
        this.aniList = aniList;
    }

    public boolean hasConnection() {
        return store.hasConnection();
    }

    public void syncNow(Consumer<Result> callback) {
        if (!store.hasConnection()) {
            callback.accept(Result.failure(app.getString(R.string.sync_not_connected)));
            return;
        }
        app.async(
                this::syncBlocking,
                callback,
                error -> callback.accept(Result.failure(errorMessage(error)))
        );
    }

    public void autoSync(Consumer<Result> callback) {
        if (!store.hasConnection()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = store.lastSync();
        if (last > 0 && now - last < AUTO_SYNC_INTERVAL) {
            return;
        }
        syncNow(callback == null ? result -> {
        } : callback);
    }

    public void pushEpisode(String animeId, String title, String episodeNumber, boolean completed) {
        if (!store.hasConnection() || animeId == null || title == null) {
            return;
        }
        int progress = progress(episodeNumber);
        if (progress <= 0) {
            return;
        }
        app.async(
                () -> {
                    pushOne(animeId, title, progress, completed);
                    return null;
                },
                ignored -> {
                },
                error -> app.log("Sync upload failed: " + errorMessage(error))
        );
    }

    private void pushOne(String animeId, String title, int progress, boolean completed)
            throws IOException {
        SyncStore.Mapping mapping = ensureMapping(animeId, title);
        if (mapping == null) {
            return;
        }
        boolean finished = completed || (mapping.episodes > 0 && progress >= mapping.episodes);
        IOException failure = null;
        if (store.isConnected(SyncStore.MAL) && mapping.malId > 0) {
            try {
                String token = malToken();
                int remote = -1;
                try {
                    remote = mal.remoteProgress(token, mapping.malId);
                } catch (Exception ignored) {
                }
                if (progress >= remote) {
                    mal.update(token, mapping.malId, progress, finished);
                }
            } catch (Exception error) {
                failure = asIOException(error);
            }
        }
        if (store.isConnected(SyncStore.ANILIST) && mapping.anilistId > 0) {
            try {
                String token = store.token(SyncStore.ANILIST);
                int remote = -1;
                try {
                    remote = aniList.remoteProgress(token, mapping.anilistId);
                } catch (Exception ignored) {
                }
                if (progress >= remote) {
                    aniList.update(token, mapping.anilistId, progress, finished);
                }
            } catch (Exception error) {
                if (failure == null) {
                    failure = asIOException(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Result syncBlocking() throws Exception {
        String firstError = null;
        boolean malConnected = store.isConnected(SyncStore.MAL);
        boolean aniConnected = store.isConnected(SyncStore.ANILIST);

        String malToken = null;
        if (malConnected) {
            try {
                malToken = malToken();
            } catch (Exception error) {
                malConnected = false;
                firstError = first(firstError, error);
            }
        }

        String aniToken = aniConnected ? store.token(SyncStore.ANILIST) : null;
        int aniUserId = 0;
        if (aniConnected) {
            try {
                aniUserId = aniList.viewer(aniToken).id;
            } catch (Exception error) {
                aniConnected = false;
                firstError = first(firstError, error);
            }
        }
        if (!malConnected && !aniConnected) {
            throw new IOException(firstError == null
                    ? app.getString(R.string.sync_not_connected)
                    : firstError);
        }

        List<RemoteEntry> remote = new ArrayList<>();
        boolean malListOk = false;
        boolean aniListOk = false;
        if (malConnected) {
            try {
                remote.addAll(mal.list(malToken));
                malListOk = true;
            } catch (Exception error) {
                firstError = first(firstError, error);
            }
        }
        if (aniConnected && aniUserId > 0) {
            try {
                remote.addAll(aniList.list(aniToken, aniUserId));
                aniListOk = true;
            } catch (Exception error) {
                firstError = first(firstError, error);
            }
        }

        LinkedHashMap<String, Candidate> candidates = candidates();
        for (Candidate candidate : candidates.values()) {
            if (candidate.history == null) {
                continue;
            }
            try {
                candidate.mapping = ensureMapping(candidate.animeId, candidate.title);
            } catch (Exception error) {
                firstError = first(firstError, error);
            }
        }

        int pulled = 0;
        for (RemoteEntry entry : remote) {
            Candidate candidate = match(entry, candidates);
            if (candidate == null) {
                continue;
            }
            merge(candidate, entry);
            if (entry.progress <= 0 || entry.progress <= candidate.localProgress()) {
                continue;
            }
            try {
                if (applyRemote(candidate, entry)) {
                    pulled++;
                }
            } catch (Exception error) {
                firstError = first(firstError, error);
            }
        }

        int pushed = 0;
        for (Candidate candidate : candidates.values()) {
            if (candidate.history == null) {
                continue;
            }
            int progress = progress(candidate.history.episodeNumber);
            if (progress <= 0) {
                continue;
            }
            SyncStore.Mapping mapping = candidate.mapping;
            if (mapping == null) {
                try {
                    mapping = ensureMapping(candidate.animeId, candidate.title);
                } catch (Exception error) {
                    firstError = first(firstError, error);
                }
            }
            if (mapping == null) {
                continue;
            }
            boolean completed = mapping.episodes > 0 && progress >= mapping.episodes;
            boolean uploaded = false;
            if (malConnected && malListOk && mapping.malId > 0
                    && progress >= candidate.remoteMalProgress) {
                try {
                    mal.update(malToken, mapping.malId, progress, completed);
                    uploaded = true;
                } catch (Exception error) {
                    firstError = first(firstError, error);
                }
            }
            if (aniConnected && aniListOk && mapping.anilistId > 0
                    && progress >= candidate.remoteAniListProgress) {
                try {
                    aniList.update(aniToken, mapping.anilistId, progress, completed);
                    uploaded = true;
                } catch (Exception error) {
                    firstError = first(firstError, error);
                }
            }
            if (uploaded) {
                pushed++;
            }
        }

        if (malListOk || aniListOk) {
            store.setLastSync(System.currentTimeMillis());
        }
        return new Result(pulled, pushed, firstError);
    }

    private LinkedHashMap<String, Candidate> candidates() {
        LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
        for (HistoryEntry entry : app.history().getAll()) {
            Candidate candidate = new Candidate(entry.animeId, entry.title, entry.posterUrl);
            candidate.history = entry;
            candidates.put(entry.animeId, candidate);
        }
        for (Anime anime : app.favourites().getAll()) {
            if (!candidates.containsKey(anime.id)) {
                candidates.put(anime.id, new Candidate(anime.id, anime.title, anime.posterUrl));
            }
        }
        return candidates;
    }

    private Candidate match(RemoteEntry entry, Map<String, Candidate> candidates) {
        for (Candidate candidate : candidates.values()) {
            SyncStore.Mapping mapping = candidate.mapping;
            if (mapping == null) {
                continue;
            }
            if (entry.malId > 0 && mapping.malId == entry.malId) {
                return candidate;
            }
            if (entry.anilistId > 0 && mapping.anilistId == entry.anilistId) {
                return candidate;
            }
        }

        Candidate best = null;
        double bestScore = 0d;
        for (Candidate candidate : candidates.values()) {
            double score = TitleMatch.score(candidate.title, entry.titles);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= TitleMatch.MIN_SCORE ? best : null;
    }

    private void merge(Candidate candidate, RemoteEntry entry) {
        SyncStore.Mapping current = candidate.mapping;
        int malId = entry.malId > 0 ? entry.malId : current == null ? 0 : current.malId;
        int anilistId = entry.anilistId > 0
                ? entry.anilistId
                : current == null ? 0 : current.anilistId;
        int episodes = entry.totalEpisodes > 0
                ? entry.totalEpisodes
                : current == null ? 0 : current.episodes;
        candidate.mapping = new SyncStore.Mapping(malId, anilistId, episodes);
        store.putMapping(candidate.animeId, candidate.mapping);
        if (entry.malId > 0) {
            candidate.remoteMalProgress = Math.max(candidate.remoteMalProgress, entry.progress);
        }
        if (entry.anilistId > 0) {
            candidate.remoteAniListProgress = Math.max(candidate.remoteAniListProgress,
                    entry.progress);
        }
    }

    private boolean applyRemote(Candidate candidate, RemoteEntry entry) throws IOException {
        List<Episode> episodes = app.animeSource(candidate.animeId)
                .episodes(candidate.animeId, false);
        Episode target = null;
        for (Episode episode : episodes) {
            if (Math.abs(EpisodeNumbers.value(episode.number) - entry.progress) < 0.0001d) {
                target = episode;
                break;
            }
        }
        if (target == null) {
            return false;
        }

        HistoryEntry existing = app.history().latestForAnime(candidate.animeId);
        if (existing != null && Math.abs(EpisodeNumbers.value(existing.episodeNumber)
                - entry.progress) < 0.0001d) {
            return false;
        }
        String title = candidate.title;
        if (title == null || title.isEmpty()) {
            title = entry.titles.isEmpty() ? "" : entry.titles.get(0);
        }
        String mode = existing == null ? "sub" : existing.mode;
        app.history().upsert(candidate.animeId, title, candidate.poster,
                target.number, target.id, mode, 0L);
        candidate.history = app.history().latestForAnime(candidate.animeId);
        return true;
    }

    private SyncStore.Mapping ensureMapping(String animeId, String title) throws IOException {
        if (animeId == null || title == null || title.isEmpty()) {
            return null;
        }
        SyncStore.Mapping cached = store.mapping(animeId);
        boolean needMal = store.isConnected(SyncStore.MAL)
                && (cached == null || cached.malId <= 0);
        boolean needAniList = store.isConnected(SyncStore.ANILIST)
                && (cached == null || cached.anilistId <= 0);
        if (cached != null && !cached.isEmpty() && !needMal && !needAniList) {
            return cached;
        }

        int malId = cached == null ? 0 : cached.malId;
        int anilistId = cached == null ? 0 : cached.anilistId;
        int episodes = cached == null ? 0 : cached.episodes;
        IOException failure = null;

        if (needMal) {
            try {
                MalApi.SearchResult result = mal.search(malToken(), title);
                if (result != null) {
                    malId = result.id;
                    if (episodes <= 0) {
                        episodes = result.episodes;
                    }
                }
            } catch (Exception error) {
                failure = asIOException(error);
            }
        }

        if (needAniList) {
            try {
                AniListApi.Media media = aniList.search(title);
                if (media != null) {
                    anilistId = media.id;
                    if (malId <= 0) {
                        malId = media.idMal;
                    }
                    if (episodes <= 0) {
                        episodes = media.episodes;
                    }
                }
            } catch (Exception error) {
                if (failure == null) {
                    failure = asIOException(error);
                }
            }
        }

        if (anilistId <= 0 && malId > 0 && store.isConnected(SyncStore.ANILIST)) {
            AniListApi.Media media = aniList.byMalId(malId);
            if (media != null) {
                anilistId = media.id;
                if (episodes <= 0) {
                    episodes = media.episodes;
                }
            }
        }

        SyncStore.Mapping mapping = new SyncStore.Mapping(malId, anilistId, episodes);
        if (mapping.isEmpty()) {
            if (failure != null) {
                throw failure;
            }
            return null;
        }
        store.putMapping(animeId, mapping);
        return mapping;
    }

    private String malToken() throws IOException {
        String token = store.token(SyncStore.MAL);
        if (token.isEmpty()) {
            throw new IOException(app.getString(R.string.sync_not_connected));
        }
        long expiresAt = store.expiresAt(SyncStore.MAL);
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - TOKEN_REFRESH_WINDOW) {
            String refresh = store.refreshToken(SyncStore.MAL);
            if (!refresh.isEmpty()) {
                MalApi.Token refreshed = mal.refresh(store.clientId(SyncStore.MAL),
                        store.clientSecret(SyncStore.MAL), refresh);
                long newExpiry = refreshed.expiresInSeconds > 0
                        ? System.currentTimeMillis() + refreshed.expiresInSeconds * 1000L
                        : 0L;
                store.saveSession(SyncStore.MAL, refreshed.accessToken,
                        refreshed.refreshToken, newExpiry, store.userName(SyncStore.MAL));
                token = refreshed.accessToken;
            }
        }
        return token;
    }

    private static int progress(String episodeNumber) {
        double value = EpisodeNumbers.value(episodeNumber);
        if (value < 1d || value != Math.rint(value)) {
            return 0;
        }
        return (int) value;
    }

    private static String first(String current, Throwable error) {
        return current != null ? current : errorMessage(error);
    }

    private static String errorMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isEmpty() ? "Sync failed" : message;
    }

    private static IOException asIOException(Throwable error) {
        return error instanceof IOException
                ? (IOException) error
                : new IOException(errorMessage(error), error);
    }

    private static final class Candidate {
        final String animeId;
        final String title;
        final String poster;
        HistoryEntry history;
        SyncStore.Mapping mapping;
        int remoteMalProgress = -1;
        int remoteAniListProgress = -1;

        Candidate(String animeId, String title, String poster) {
            this.animeId = animeId;
            this.title = title == null ? "" : title;
            this.poster = poster == null ? "" : poster;
        }

        int localProgress() {
            if (history == null) {
                return 0;
            }
            double value = EpisodeNumbers.value(history.episodeNumber);
            return value < 1d ? 0 : (int) Math.floor(value);
        }
    }

    public static final class Result {
        public final int pulled;
        public final int pushed;
        public final String error;

        public Result(int pulled, int pushed, String error) {
            this.pulled = pulled;
            this.pushed = pushed;
            this.error = error;
        }

        public static Result failure(String message) {
            return new Result(0, 0, message);
        }

        public boolean hasError() {
            return error != null && !error.isEmpty();
        }
    }
}
