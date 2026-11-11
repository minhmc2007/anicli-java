package com.minhmc2077.aniclijava.sync;

import com.minhmc2077.aniclijava.net.CronetHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AniListApi {
    private static final String ENDPOINT = "https://graphql.anilist.co";

    private static final String MEDIA_FIELDS =
            "id idMal episodes title { romaji english native } synonyms";

    private static final String SEARCH_QUERY =
            "query ($search: String) { Page(perPage: 10) { media(search: $search, type: ANIME) { "
                    + MEDIA_FIELDS + " } } }";

    private static final String BY_MAL_QUERY =
            "query ($idMal: Int) { Media(idMal: $idMal, type: ANIME) { "
                    + MEDIA_FIELDS + " } }";

    private static final String VIEWER_QUERY =
            "query { Viewer { id name } }";

    private static final String LIST_QUERY =
            "query ($userId: Int) { MediaListCollection(type: ANIME, userId: $userId) { lists { "
                    + "entries { progress media { " + MEDIA_FIELDS + " } } } } }";

    private static final String UPDATE_MUTATION =
            "mutation ($mediaId: Int, $progress: Int, $status: MediaListStatus) { "
                    + "SaveMediaListEntry(mediaId: $mediaId, progress: $progress, status: $status) { "
                    + "id progress status } }";

    private final CronetHttp http;

    public AniListApi(CronetHttp http) {
        this.http = http;
    }

    public Media search(String title) throws IOException {
        JSONObject variables = new JSONObject();
        try {
            variables.put("search", title);
        } catch (JSONException ignored) {
            return null;
        }
        JSONObject data = graphql("", SEARCH_QUERY, variables);
        JSONObject page = data.optJSONObject("Page");
        JSONArray media = page == null ? null : page.optJSONArray("media");
        Media best = null;
        double bestScore = 0d;
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                Media candidate = media(media.optJSONObject(i));
                if (candidate == null) {
                    continue;
                }
                double score = TitleMatch.score(title, candidate.titles);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best != null && bestScore >= TitleMatch.MIN_SCORE ? best : null;
    }

    public Media byMalId(int malId) {
        if (malId <= 0) {
            return null;
        }
        try {
            JSONObject variables = new JSONObject();
            variables.put("idMal", malId);
            JSONObject data = graphql("", BY_MAL_QUERY, variables);
            return media(data.optJSONObject("Media"));
        } catch (Exception ignored) {
            return null;
        }
    }

    public Viewer viewer(String token) throws IOException {
        JSONObject data = graphql(token, VIEWER_QUERY, null);
        JSONObject viewer = data.optJSONObject("Viewer");
        if (viewer == null) {
            throw new IOException("Could not read the AniList account");
        }
        return new Viewer(viewer.optInt("id", 0), viewer.optString("name", ""));
    }

    public List<RemoteEntry> list(String token, int userId) throws IOException {
        JSONObject variables = new JSONObject();
        try {
            variables.put("userId", userId);
        } catch (JSONException error) {
            throw new IOException("Could not read the AniList list", error);
        }
        JSONObject data = graphql(token, LIST_QUERY, variables);
        JSONObject collection = data.optJSONObject("MediaListCollection");
        JSONArray lists = collection == null ? null : collection.optJSONArray("lists");
        Map<Integer, RemoteEntry> entries = new LinkedHashMap<>();
        if (lists != null) {
            for (int i = 0; i < lists.length(); i++) {
                JSONObject list = lists.optJSONObject(i);
                JSONArray listEntries = list == null ? null : list.optJSONArray("entries");
                if (listEntries == null) {
                    continue;
                }
                for (int j = 0; j < listEntries.length(); j++) {
                    JSONObject entry = listEntries.optJSONObject(j);
                    if (entry == null) {
                        continue;
                    }
                    Media media = media(entry.optJSONObject("media"));
                    if (media == null || media.id <= 0) {
                        continue;
                    }
                    int progress = entry.optInt("progress", 0);
                    RemoteEntry previous = entries.get(media.id);
                    if (previous != null && previous.progress >= progress) {
                        continue;
                    }
                    entries.put(media.id, new RemoteEntry(media.idMal, media.id, progress,
                            media.episodes, media.titles));
                }
            }
        }
        return new ArrayList<>(entries.values());
    }

    public int remoteProgress(String token, int mediaId) throws IOException {
        JSONObject variables = new JSONObject();
        try {
            variables.put("mediaId", mediaId);
        } catch (JSONException error) {
            return -1;
        }
        JSONObject data = graphql(token,
                "query ($mediaId: Int) { Media(id: $mediaId) { mediaListEntry { progress } } }",
                variables);
        JSONObject media = data.optJSONObject("Media");
        JSONObject entry = media == null ? null : media.optJSONObject("mediaListEntry");
        return entry == null ? -1 : entry.optInt("progress", -1);
    }

    public void update(String token, int mediaId, int progress, boolean completed)
            throws IOException {
        JSONObject variables = new JSONObject();
        try {
            variables.put("mediaId", mediaId);
            variables.put("progress", progress);
            variables.put("status", completed ? "COMPLETED" : "CURRENT");
        } catch (JSONException error) {
            throw new IOException("Could not update AniList", error);
        }
        graphql(token, UPDATE_MUTATION, variables);
    }

    private JSONObject graphql(String token, String query, JSONObject variables)
            throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("query", query);
            if (variables != null) {
                payload.put("variables", variables);
            }
        } catch (JSONException error) {
            throw new IOException("Could not build the AniList request", error);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }

        CronetHttp.Response response = http.request("POST", ENDPOINT, headers,
                payload.toString(), "application/json");
        JSONObject root;
        try {
            root = new JSONObject(response.text());
        } catch (JSONException error) {
            throw new IOException("Invalid AniList response", error);
        }

        JSONArray errors = root.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            JSONObject first = errors.optJSONObject(0);
            String message = first == null ? "" : first.optString("message", "");
            throw new IOException(message.isEmpty() ? "AniList request failed" : message);
        }
        if (!response.isSuccessful()) {
            throw new IOException("AniList HTTP " + response.statusCode);
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new IOException("AniList returned no data");
        }
        return data;
    }

    private static Media media(JSONObject object) {
        if (object == null) {
            return null;
        }
        int id = object.optInt("id", 0);
        if (id <= 0) {
            return null;
        }
        List<String> titles = new ArrayList<>();
        JSONObject title = object.optJSONObject("title");
        if (title != null) {
            add(titles, title.optString("romaji", ""));
            add(titles, title.optString("english", ""));
            add(titles, title.optString("native", ""));
        }
        JSONArray synonyms = object.optJSONArray("synonyms");
        if (synonyms != null) {
            for (int i = 0; i < synonyms.length(); i++) {
                add(titles, synonyms.optString(i, ""));
            }
        }
        return new Media(id, object.optInt("idMal", 0), object.optInt("episodes", 0), titles);
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.isEmpty()) {
            values.add(value);
        }
    }

    public static final class Media {
        public final int id;
        public final int idMal;
        public final int episodes;
        public final List<String> titles;

        public Media(int id, int idMal, int episodes, List<String> titles) {
            this.id = id;
            this.idMal = idMal;
            this.episodes = episodes;
            this.titles = titles;
        }
    }

    public static final class Viewer {
        public final int id;
        public final String name;

        public Viewer(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
