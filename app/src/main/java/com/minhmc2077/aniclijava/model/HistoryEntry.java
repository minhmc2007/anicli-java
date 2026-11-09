package com.minhmc2077.aniclijava.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One entry in the watch history. Like ani-cli, there is one entry per anime:
 * playing a newer episode replaces the previous episode for that anime.
 */
public final class HistoryEntry {
    public final String animeId;
    public final String title;
    public final String posterUrl;
    public final String episodeNumber;
    public final String episodeId;
    public final String mode;
    public final long positionMs;
    public final long updatedAt;

    public HistoryEntry(String animeId, String title, String posterUrl, String episodeNumber,
                        String episodeId, String mode, long positionMs, long updatedAt) {
        this.animeId = animeId;
        this.title = title;
        this.posterUrl = posterUrl;
        this.episodeNumber = episodeNumber;
        this.episodeId = episodeId;
        this.mode = mode;
        this.positionMs = positionMs;
        this.updatedAt = updatedAt;
    }

    public HistoryEntry withPosition(long newPositionMs) {
        return new HistoryEntry(animeId, title, posterUrl, episodeNumber, episodeId, mode,
                newPositionMs, updatedAt);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("animeId", animeId == null ? "" : animeId);
        o.put("title", title == null ? "" : title);
        o.put("poster", posterUrl == null ? "" : posterUrl);
        o.put("episode", episodeNumber == null ? "" : episodeNumber);
        o.put("episodeId", episodeId == null ? "" : episodeId);
        o.put("mode", mode == null ? "sub" : mode);
        o.put("position", positionMs);
        o.put("updatedAt", updatedAt);
        return o;
    }

    public static HistoryEntry fromJson(JSONObject o) {
        return new HistoryEntry(
                o.optString("animeId", ""),
                o.optString("title", ""),
                o.optString("poster", ""),
                o.optString("episode", ""),
                o.optString("episodeId", ""),
                o.optString("mode", "sub"),
                o.optLong("position", 0L),
                o.optLong("updatedAt", 0L)
        );
    }
}
