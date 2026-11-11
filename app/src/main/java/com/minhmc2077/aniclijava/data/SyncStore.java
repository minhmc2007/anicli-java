package com.minhmc2077.aniclijava.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhmc2077.aniclijava.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

public final class SyncStore {
    public static final String MAL = "mal";
    public static final String ANILIST = "anilist";
    public static final String[] SERVICES = {MAL, ANILIST};

    private static final String PREFS = "anicli_sync";
    private static final String KEY_LAST_SYNC = "last_sync";

    private final SharedPreferences preferences;

    public SyncStore(Context context) {
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isConnected(String service) {
        return !token(service).isEmpty();
    }

    public boolean hasConnection() {
        for (String service : SERVICES) {
            if (isConnected(service)) {
                return true;
            }
        }
        return false;
    }

    public String token(String service) {
        return preferences.getString("token_" + service, "");
    }

    public String refreshToken(String service) {
        return preferences.getString("refresh_" + service, "");
    }

    public long expiresAt(String service) {
        return preferences.getLong("expires_" + service, 0L);
    }

    public String userName(String service) {
        return preferences.getString("name_" + service, "");
    }

    public void saveSession(String service, String token, String refreshToken,
                            long expiresAt, String userName) {
        preferences.edit()
                .putString("token_" + service, token == null ? "" : token)
                .putString("refresh_" + service, refreshToken == null ? "" : refreshToken)
                .putLong("expires_" + service, expiresAt)
                .putString("name_" + service, userName == null ? "" : userName)
                .apply();
    }

    public void clearSession(String service) {
        preferences.edit()
                .remove("token_" + service)
                .remove("refresh_" + service)
                .remove("expires_" + service)
                .remove("name_" + service)
                .apply();
    }

    public String clientId(String service) {
        String saved = preferences.getString("client_" + service, "");
        return saved == null || saved.isEmpty() ? defaultClientId(service) : saved;
    }

    public String clientSecret(String service) {
        String saved = preferences.getString("secret_" + service, "");
        return saved == null || saved.isEmpty() ? defaultClientSecret(service) : saved;
    }

    public void setClient(String service, String clientId, String clientSecret) {
        preferences.edit()
                .putString("client_" + service, clientId == null ? "" : clientId.trim())
                .putString("secret_" + service, clientSecret == null ? "" : clientSecret.trim())
                .apply();
    }

    public String redirectUri(String service) {
        return "aniclijava://oauth/" + service;
    }

    public void setPending(String service, String state, String verifier) {
        preferences.edit()
                .putString("pending_state_" + service, state == null ? "" : state)
                .putString("pending_verifier_" + service, verifier == null ? "" : verifier)
                .apply();
    }

    public String pendingState(String service) {
        return preferences.getString("pending_state_" + service, "");
    }

    public String pendingVerifier(String service) {
        return preferences.getString("pending_verifier_" + service, "");
    }

    public void clearPending(String service) {
        preferences.edit()
                .remove("pending_state_" + service)
                .remove("pending_verifier_" + service)
                .apply();
    }

    public Mapping mapping(String animeId) {
        if (animeId == null) {
            return null;
        }
        String raw = preferences.getString("mapping_" + animeId, "");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(raw);
            return new Mapping(
                    object.optInt("mal", 0),
                    object.optInt("anilist", 0),
                    object.optInt("episodes", 0)
            );
        } catch (JSONException ignored) {
            return null;
        }
    }

    public void putMapping(String animeId, Mapping mapping) {
        if (animeId == null || mapping == null || mapping.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject();
            object.put("mal", mapping.malId);
            object.put("anilist", mapping.anilistId);
            object.put("episodes", mapping.episodes);
            preferences.edit().putString("mapping_" + animeId, object.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public long lastSync() {
        return preferences.getLong(KEY_LAST_SYNC, 0L);
    }

    public void setLastSync(long timestamp) {
        preferences.edit().putLong(KEY_LAST_SYNC, timestamp).apply();
    }

    private static String defaultClientId(String service) {
        return MAL.equals(service) ? BuildConfig.MAL_CLIENT_ID : BuildConfig.ANILIST_CLIENT_ID;
    }

    private static String defaultClientSecret(String service) {
        return MAL.equals(service) ? BuildConfig.MAL_CLIENT_SECRET : "";
    }

    public static final class Mapping {
        public final int malId;
        public final int anilistId;
        public final int episodes;

        public Mapping(int malId, int anilistId, int episodes) {
            this.malId = malId;
            this.anilistId = anilistId;
            this.episodes = episodes;
        }

        public boolean isEmpty() {
            return malId <= 0 && anilistId <= 0;
        }
    }
}
