package com.minhmc2077.aniclijava.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhmc2077.aniclijava.model.Anime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class FavouriteStore {
    private static final String PREFS = "anicli_favourites";
    private static final String KEY = "items";

    private final SharedPreferences preferences;

    public FavouriteStore(Context context) {
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Anime> getAll() {
        List<Anime> result = new ArrayList<>();
        String raw = preferences.getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    result.add(Anime.fromJson(object));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public synchronized boolean isFavourite(String id) {
        if (id == null) {
            return false;
        }
        for (Anime anime : getAll()) {
            if (id.equals(anime.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when the anime is now a favourite, false when it was removed.
     */
    public synchronized boolean toggle(Anime anime) {
        List<Anime> items = getAll();
        boolean removed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).id.equals(anime.id)) {
                items.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            items.add(0, anime);
        }
        save(items);
        return !removed;
    }

    public synchronized void remove(String id) {
        List<Anime> items = getAll();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
            }
        }
        save(items);
    }

    private void save(List<Anime> items) {
        JSONArray array = new JSONArray();
        for (Anime anime : items) {
            try {
                array.put(anime.toJson());
            } catch (Exception ignored) {
            }
        }
        preferences.edit().putString(KEY, array.toString()).apply();
    }
}
