package com.minhmc2077.aniclijava.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.util.EpisodeNumbers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HistoryStore {
    private static final String PREFS = "anicli_history";
    private static final String KEY = "items";
    private static final int MAX_ENTRIES = 100;

    private final SharedPreferences preferences;

    public HistoryStore(Context context) {
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<HistoryEntry> getAll() {
        List<HistoryEntry> result = new ArrayList<>();
        String raw = preferences.getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    result.add(HistoryEntry.fromJson(object));
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(result, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    public synchronized HistoryEntry find(String animeId, String episodeNumber) {
        if (animeId == null || episodeNumber == null) {
            return null;
        }
        for (HistoryEntry entry : getAll()) {
            if (animeId.equals(entry.animeId) && episodeNumber.equals(entry.episodeNumber)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Returns the highest episode number watched for an anime. History keeps one
     * entry per anime, but comparing numerically also handles specials like 5.5.
     */
    public synchronized HistoryEntry latestForAnime(String animeId) {
        if (animeId == null) {
            return null;
        }
        HistoryEntry best = null;
        double bestValue = -1d;
        for (HistoryEntry entry : getAll()) {
            if (!animeId.equals(entry.animeId)) {
                continue;
            }
            double value = EpisodeNumbers.value(entry.episodeNumber);
            if (value >= 0d && value > bestValue) {
                bestValue = value;
                best = entry;
            }
        }
        return best;
    }

    public synchronized void upsert(String animeId, String title, String posterUrl,
                                    String episodeNumber, String episodeId, String mode,
                                    long positionMs) {
        List<HistoryEntry> items = getAll();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (animeId.equals(items.get(i).animeId)) {
                items.remove(i);
            }
        }
        items.add(0, new HistoryEntry(animeId, title, posterUrl, episodeNumber, episodeId,
                mode, Math.max(0, positionMs), System.currentTimeMillis()));
        if (items.size() > MAX_ENTRIES) {
            items = new ArrayList<>(items.subList(0, MAX_ENTRIES));
        }
        save(items);
    }

    public synchronized void updatePosition(String animeId, String episodeNumber, long positionMs) {
        List<HistoryEntry> items = getAll();
        for (int i = 0; i < items.size(); i++) {
            HistoryEntry entry = items.get(i);
            if (entry.animeId.equals(animeId) && entry.episodeNumber.equals(episodeNumber)) {
                items.set(i, new HistoryEntry(entry.animeId, entry.title, entry.posterUrl,
                        entry.episodeNumber, entry.episodeId, entry.mode,
                        Math.max(0, positionMs), System.currentTimeMillis()));
                break;
            }
        }
        save(items);
    }

    public synchronized void remove(String animeId) {
        List<HistoryEntry> items = getAll();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (animeId.equals(items.get(i).animeId)) {
                items.remove(i);
            }
        }
        save(items);
    }

    public synchronized void clear() {
        preferences.edit().putString(KEY, "[]").apply();
    }

    private void save(List<HistoryEntry> items) {
        JSONArray array = new JSONArray();
        for (HistoryEntry entry : items) {
            try {
                array.put(entry.toJson());
            } catch (Exception ignored) {
            }
        }
        preferences.edit().putString(KEY, array.toString()).apply();
    }
}
