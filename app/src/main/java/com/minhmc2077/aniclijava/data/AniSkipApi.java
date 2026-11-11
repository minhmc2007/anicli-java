package com.minhmc2077.aniclijava.data;

import com.minhmc2077.aniclijava.model.SkipTime;
import com.minhmc2077.aniclijava.net.CronetHttp;
import com.minhmc2077.aniclijava.util.EpisodeNumbers;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AniSkipApi {
    private static final String ENDPOINT =
            "https://api.aniskip.com/v2/skip-times/%d/%s"
                    + "?types=op&types=ed&episodeLength=%d";

    private final CronetHttp http;
    private final Map<String, List<SkipTime>> cache = new ConcurrentHashMap<>();

    public AniSkipApi(CronetHttp http) {
        this.http = http;
    }

    public List<SkipTime> fetch(int malId, String episodeNumber, long durationMs)
            throws IOException {
        double episode = EpisodeNumbers.value(episodeNumber);
        if (malId <= 0 || episode < 0d) {
            return Collections.emptyList();
        }

        String episodeParam = formatEpisode(episode);
        long length = Math.max(0L, Math.round(durationMs / 1000d));
        String key = malId + ":" + episodeParam + ":" + length;
        List<SkipTime> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        String url = String.format(Locale.US, ENDPOINT, malId, episodeParam, length);
        CronetHttp.Response response = http.get(url, headers, true);
        if (response.statusCode == 404) {
            cache.put(key, Collections.emptyList());
            return Collections.emptyList();
        }
        if (!response.isSuccessful()) {
            throw new IOException("AniSkip HTTP " + response.statusCode);
        }

        List<SkipTime> times = parse(response);
        cache.put(key, times);
        return times;
    }

    private static List<SkipTime> parse(CronetHttp.Response response) throws IOException {
        try {
            JSONObject root = new JSONObject(response.text());
            if (!root.optBoolean("found", false)) {
                return Collections.emptyList();
            }
            JSONArray results = root.optJSONArray("results");
            if (results == null) {
                return Collections.emptyList();
            }
            List<SkipTime> times = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject entry = results.optJSONObject(i);
                JSONObject interval = entry == null ? null : entry.optJSONObject("interval");
                if (interval == null) {
                    continue;
                }
                times.add(new SkipTime(
                        entry.optString("skipType", ""),
                        Math.round(interval.optDouble("startTime", 0d) * 1000d),
                        Math.round(interval.optDouble("endTime", 0d) * 1000d)
                ));
            }
            return times;
        } catch (JSONException error) {
            throw new IOException("Invalid AniSkip response", error);
        }
    }

    private static String formatEpisode(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return String.format(Locale.US, "%.2f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }
}
