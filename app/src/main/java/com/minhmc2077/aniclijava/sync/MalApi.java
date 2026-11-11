package com.minhmc2077.aniclijava.sync;

import android.net.Uri;

import com.minhmc2077.aniclijava.net.CronetHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MalApi {
    private static final String API = "https://api.myanimelist.net/v2";
    private static final String TOKEN_URL = "https://myanimelist.net/v1/oauth2/token";
    private static final String FORM = "application/x-www-form-urlencoded";

    private final CronetHttp http;

    public MalApi(CronetHttp http) {
        this.http = http;
    }

    public Token exchange(String clientId, String clientSecret, String code,
                          String verifier, String redirectUri) throws IOException {
        StringBuilder body = new StringBuilder()
                .append("grant_type=authorization_code")
                .append("&client_id=").append(encode(clientId))
                .append("&code=").append(encode(code))
                .append("&redirect_uri=").append(encode(redirectUri))
                .append("&code_verifier=").append(encode(verifier));
        if (clientSecret != null && !clientSecret.isEmpty()) {
            body.append("&client_secret=").append(encode(clientSecret));
        }
        return parseToken(tokenRequest(body.toString()));
    }

    public Token refresh(String clientId, String clientSecret, String refreshToken)
            throws IOException {
        StringBuilder body = new StringBuilder()
                .append("grant_type=refresh_token")
                .append("&refresh_token=").append(encode(refreshToken))
                .append("&client_id=").append(encode(clientId));
        if (clientSecret != null && !clientSecret.isEmpty()) {
            body.append("&client_secret=").append(encode(clientSecret));
        }
        return parseToken(tokenRequest(body.toString()));
    }

    public String userName(String token) throws IOException {
        JSONObject root = getJson(API + "/users/@me?fields=name", token);
        return root.optString("name", "");
    }

    public List<RemoteEntry> list(String token) throws IOException {
        Map<Integer, RemoteEntry> entries = new LinkedHashMap<>();
        String url = API + "/users/@me/animelist"
                + "?fields=list_status,num_episodes,alternative_titles&limit=1000&nsfw=true";
        for (int page = 0; page < 20 && url != null && !url.isEmpty(); page++) {
            JSONObject root = getJson(url, token);
            JSONArray data = root.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject entry = data.optJSONObject(i);
                    if (entry == null) {
                        continue;
                    }
                    JSONObject node = entry.optJSONObject("node");
                    JSONObject status = entry.optJSONObject("list_status");
                    if (node == null || status == null) {
                        continue;
                    }
                    int id = node.optInt("id", 0);
                    if (id <= 0) {
                        continue;
                    }
                    int progress = status.optInt("num_episodes_watched",
                            status.optInt("num_watched_episodes", 0));
                    RemoteEntry previous = entries.get(id);
                    if (previous != null && previous.progress >= progress) {
                        continue;
                    }
                    entries.put(id, new RemoteEntry(id, 0, progress,
                            node.optInt("num_episodes", 0), titles(node)));
                }
            }
            JSONObject paging = root.optJSONObject("paging");
            url = paging == null ? null : paging.optString("next", "");
        }
        return new ArrayList<>(entries.values());
    }

    public SearchResult search(String token, String title) throws IOException {
        String url = API + "/anime?q=" + Uri.encode(title)
                + "&limit=10&fields=id,title,alternative_titles,num_episodes";
        JSONObject root = getJson(url, token);
        JSONArray data = root.optJSONArray("data");
        SearchResult best = null;
        double bestScore = 0d;
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject entry = data.optJSONObject(i);
                JSONObject node = entry == null ? null : entry.optJSONObject("node");
                if (node == null) {
                    continue;
                }
                double score = TitleMatch.score(title, titles(node));
                if (score > bestScore) {
                    bestScore = score;
                    best = new SearchResult(node.optInt("id", 0),
                            node.optInt("num_episodes", 0), score);
                }
            }
        }
        return best != null && best.score >= TitleMatch.MIN_SCORE ? best : null;
    }

    public int remoteProgress(String token, int animeId) throws IOException {
        JSONObject root = getJson(API + "/anime/" + animeId + "?fields=my_list_status", token);
        JSONObject status = root.optJSONObject("my_list_status");
        if (status == null) {
            return -1;
        }
        return status.optInt("num_episodes_watched",
                status.optInt("num_watched_episodes", -1));
    }

    public void update(String token, int animeId, int progress, boolean completed)
            throws IOException {
        String body = "num_watched_episodes=" + progress
                + "&status=" + (completed ? "completed" : "watching");
        CronetHttp.Response response = http.request("PUT",
                API + "/anime/" + animeId + "/my_list_status",
                authHeaders(token), body, FORM);
        if (!response.isSuccessful()) {
            throw new IOException(errorMessage(response, "Could not update MyAnimeList"));
        }
    }

    private JSONObject getJson(String url, String token) throws IOException {
        CronetHttp.Response response = http.get(url, authHeaders(token), true);
        if (!response.isSuccessful()) {
            throw new IOException(errorMessage(response, "MyAnimeList request failed"));
        }
        try {
            return new JSONObject(response.text());
        } catch (JSONException error) {
            throw new IOException("Invalid MyAnimeList response", error);
        }
    }

    private Token tokenRequest(String body) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        CronetHttp.Response response = http.request("POST", TOKEN_URL, headers, body, FORM);
        JSONObject json;
        try {
            json = new JSONObject(response.text());
        } catch (JSONException error) {
            throw new IOException("Invalid MyAnimeList token response", error);
        }
        if (!response.isSuccessful()) {
            throw new IOException(errorMessage(json, "MyAnimeList login failed"));
        }
        String accessToken = json.optString("access_token", "");
        if (accessToken.isEmpty()) {
            throw new IOException("MyAnimeList login failed");
        }
        return new Token(accessToken, json.optString("refresh_token", ""),
                json.optLong("expires_in", 0L));
    }

    private Token parseToken(JSONObject json) throws IOException {
        String accessToken = json.optString("access_token", "");
        if (accessToken.isEmpty()) {
            throw new IOException(errorMessage(json, "MyAnimeList login failed"));
        }
        return new Token(accessToken, json.optString("refresh_token", ""),
                json.optLong("expires_in", 0L));
    }

    private static Map<String, String> authHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    private static List<String> titles(JSONObject node) {
        List<String> titles = new ArrayList<>();
        add(titles, node.optString("title", ""));
        JSONObject alternative = node.optJSONObject("alternative_titles");
        if (alternative != null) {
            add(titles, alternative.optString("en", ""));
            add(titles, alternative.optString("ja", ""));
            JSONArray synonyms = alternative.optJSONArray("synonyms");
            if (synonyms != null) {
                for (int i = 0; i < synonyms.length(); i++) {
                    add(titles, synonyms.optString(i, ""));
                }
            }
        }
        return titles;
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.isEmpty()) {
            values.add(value);
        }
    }

    private static String errorMessage(CronetHttp.Response response, String fallback) {
        try {
            JSONObject json = new JSONObject(response.text());
            return errorMessage(json, fallback + " (HTTP " + response.statusCode + ")");
        } catch (JSONException ignored) {
            return fallback + " (HTTP " + response.statusCode + ")";
        }
    }

    private static String errorMessage(JSONObject json, String fallback) {
        String message = json.optString("message", "");
        if (!message.isEmpty()) {
            return message;
        }
        String error = json.optString("error", "");
        return error.isEmpty() ? fallback : error;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
            return "";
        }
    }

    public static final class Token {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresInSeconds;

        public Token(String accessToken, String refreshToken, long expiresInSeconds) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    public static final class SearchResult {
        public final int id;
        public final int episodes;
        public final double score;

        public SearchResult(int id, int episodes, double score) {
            this.id = id;
            this.episodes = episodes;
            this.score = score;
        }
    }
}
