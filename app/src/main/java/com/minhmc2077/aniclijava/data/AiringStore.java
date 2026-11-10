package com.minhmc2077.aniclijava.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.util.EpisodeNumbers;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Airings the user asked to be notified about, plus the latest upstream episode
 * seen for anime that are being tracked.
 */
public final class AiringStore {
    private static final String PREFS = "anicli_airing";
    private static final String KEY_SUBSCRIPTIONS = "subscriptions";
    private static final String KEY_LATEST = "latest";
    private static final String KEY_LAST_REFRESH = "last_refresh";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_POSTER = "poster";
    private static final String FIELD_LAST_NOTIFIED = "last_notified";

    private final SharedPreferences preferences;

    public AiringStore(Context context) {
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized boolean isEnabled(String animeId) {
        return animeId != null && readSubscriptions().optJSONObject(animeId) != null;
    }

    public synchronized void setEnabled(Anime anime, boolean enabled) {
        if (anime == null || anime.id == null || anime.id.isEmpty()) {
            return;
        }
        JSONObject subscriptions = readSubscriptions();
        try {
            if (enabled) {
                JSONObject entry = new JSONObject();
                entry.put(FIELD_TITLE, anime.title == null ? "" : anime.title);
                entry.put(FIELD_POSTER, anime.posterUrl == null ? "" : anime.posterUrl);
                entry.put(FIELD_LAST_NOTIFIED, "");
                subscriptions.put(anime.id, entry);
            } else {
                subscriptions.remove(anime.id);
            }
            writeSubscriptions(subscriptions);
        } catch (JSONException ignored) {
        }
    }

    public synchronized String lastNotified(String animeId) {
        JSONObject entry = animeId == null ? null : readSubscriptions().optJSONObject(animeId);
        return entry == null ? "" : entry.optString(FIELD_LAST_NOTIFIED, "");
    }

    public synchronized void setLastNotified(String animeId, String episode) {
        if (animeId == null) {
            return;
        }
        JSONObject subscriptions = readSubscriptions();
        JSONObject entry = subscriptions.optJSONObject(animeId);
        if (entry == null) {
            return;
        }
        try {
            entry.put(FIELD_LAST_NOTIFIED, episode == null ? "" : episode);
            subscriptions.put(animeId, entry);
            writeSubscriptions(subscriptions);
        } catch (JSONException ignored) {
        }
    }

    public synchronized List<Anime> subscriptions() {
        List<Anime> result = new ArrayList<>();
        JSONObject subscriptions = readSubscriptions();
        Iterator<String> keys = subscriptions.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject entry = subscriptions.optJSONObject(id);
            if (entry == null) {
                continue;
            }
            result.add(new Anime(
                    id,
                    entry.optString(FIELD_TITLE, ""),
                    entry.optString(FIELD_POSTER, ""),
                    "", 0, 0, 0, 0));
        }
        return result;
    }

    public synchronized boolean hasSubscriptions() {
        return readSubscriptions().length() > 0;
    }

    public synchronized String latest(String animeId) {
        return animeId == null ? "" : readLatest().optString(animeId, "");
    }

    public synchronized void rememberLatest(String animeId, String episode) {
        if (animeId == null || episode == null || episode.isEmpty()) {
            return;
        }
        JSONObject latest = readLatest();
        String current = latest.optString(animeId, "");
        if (!current.isEmpty() && !EpisodeNumbers.isNewer(episode, current)) {
            return;
        }
        try {
            latest.put(animeId, episode);
            writeLatest(latest);
        } catch (JSONException ignored) {
        }
    }

    public synchronized long lastRefresh() {
        return preferences.getLong(KEY_LAST_REFRESH, 0L);
    }

    public synchronized void setLastRefresh(long time) {
        preferences.edit().putLong(KEY_LAST_REFRESH, time).apply();
    }

    private JSONObject readSubscriptions() {
        return read(KEY_SUBSCRIPTIONS);
    }

    private JSONObject readLatest() {
        return read(KEY_LATEST);
    }

    private JSONObject read(String key) {
        try {
            return new JSONObject(preferences.getString(key, "{}"));
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private void writeSubscriptions(JSONObject value) {
        preferences.edit().putString(KEY_SUBSCRIPTIONS, value.toString()).apply();
    }

    private void writeLatest(JSONObject value) {
        preferences.edit().putString(KEY_LATEST, value.toString()).apply();
    }
}
