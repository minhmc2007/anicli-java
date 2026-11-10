package com.minhmc2077.aniclijava.model;

import org.json.JSONException;
import org.json.JSONObject;

public final class Anime {
    public final String id;
    public final String title;
    public final String posterUrl;
    public final String type;
    public final int episodes;
    public final int subCount;
    public final int dubCount;
    public final int rank;

    public Anime(String id, String title, String posterUrl, String type, int episodes,
                 int subCount, int dubCount, int rank) {
        this.id = id;
        this.title = title;
        this.posterUrl = posterUrl;
        this.type = type;
        this.episodes = episodes;
        this.subCount = subCount;
        this.dubCount = dubCount;
        this.rank = rank;
    }

    public static Anime simple(String id, String title, String posterUrl) {
        return new Anime(id, title, posterUrl, "", 0, 0, 0, 0);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id == null ? "" : id);
        o.put("title", title == null ? "" : title);
        o.put("poster", posterUrl == null ? "" : posterUrl);
        o.put("type", type == null ? "" : type);
        o.put("episodes", episodes);
        o.put("sub", subCount);
        o.put("dub", dubCount);
        o.put("rank", rank);
        return o;
    }

    public static Anime fromJson(JSONObject o) {
        return new Anime(
                o.optString("id", ""),
                o.optString("title", ""),
                o.optString("poster", ""),
                o.optString("type", ""),
                o.optInt("episodes", 0),
                o.optInt("sub", 0),
                o.optInt("dub", 0),
                o.optInt("rank", 0)
        );
    }
}
