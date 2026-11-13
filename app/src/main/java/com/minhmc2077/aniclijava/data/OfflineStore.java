package com.minhmc2077.aniclijava.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhmc2077.aniclijava.model.Quality;
import com.minhmc2077.aniclijava.model.StreamInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class OfflineStore {
    private static final String PREFS = "anicli_offline";
    private static final String PREFIX = "stream_";
    private static final int MAX_ENTRIES = 60;

    private final SharedPreferences preferences;

    public OfflineStore(Context context) {
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized StreamInfo stream(String animeId, String episodeNumber, String mode) {
        String raw = preferences.getString(key(animeId, episodeNumber, mode), null);
        if (raw == null) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(raw);
            List<Quality> qualities = new ArrayList<>();
            JSONArray array = object.optJSONArray("qualities");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject quality = array.optJSONObject(i);
                    if (quality != null) {
                        qualities.add(new Quality(
                                quality.optString("label", "Auto"),
                                quality.optInt("height", 0),
                                quality.optString("url", "")));
                    }
                }
            }
            return new StreamInfo(
                    object.optString("master", ""),
                    object.optString("subtitle", null),
                    object.optString("referer", ""),
                    object.optString("mal", ""),
                    qualities,
                    object.optLong("durationMs", 0L));
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized void putStream(String animeId, String episodeNumber, String mode,
                                       StreamInfo info) {
        if (info == null || info.masterUrl.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject();
            object.put("master", info.masterUrl);
            object.put("subtitle", info.subtitleUrl == null ? "" : info.subtitleUrl);
            object.put("referer", info.referer == null ? "" : info.referer);
            object.put("mal", info.malId == null ? "" : info.malId);
            object.put("durationMs", info.durationMs);
            object.put("saved", System.currentTimeMillis());
            JSONArray qualities = new JSONArray();
            for (Quality quality : info.qualities) {
                JSONObject entry = new JSONObject();
                entry.put("label", quality.label);
                entry.put("height", quality.height);
                entry.put("url", quality.url);
                qualities.put(entry);
            }
            object.put("qualities", qualities);
            preferences.edit()
                    .putString(key(animeId, episodeNumber, mode), object.toString())
                    .apply();
            prune();
        } catch (Exception ignored) {
        }
    }

    public synchronized void removeStream(String animeId, String episodeNumber, String mode) {
        preferences.edit().remove(key(animeId, episodeNumber, mode)).apply();
    }

    private void prune() {
        Map<String, ?> values = preferences.getAll();
        if (values.size() <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<String, ?>> entries = new ArrayList<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey().startsWith(PREFIX)) {
                entries.add(entry);
            }
        }
        Collections.sort(entries, new Comparator<Map.Entry<String, ?>>() {
            @Override
            public int compare(Map.Entry<String, ?> first, Map.Entry<String, ?> second) {
                return Long.compare(savedAt(first.getValue()), savedAt(second.getValue()));
            }
        });
        SharedPreferences.Editor editor = preferences.edit();
        int remove = entries.size() - MAX_ENTRIES;
        for (int i = 0; i < remove; i++) {
            editor.remove(entries.get(i).getKey());
        }
        editor.apply();
    }

    private static long savedAt(Object value) {
        try {
            return new JSONObject(String.valueOf(value)).optLong("saved", 0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String key(String animeId, String episodeNumber, String mode) {
        return PREFIX + animeId + "|" + episodeNumber + "|"
                + ("dub".equalsIgnoreCase(mode) ? "dub" : "sub");
    }
}
