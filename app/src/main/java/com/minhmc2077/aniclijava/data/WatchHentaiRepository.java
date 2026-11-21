package com.minhmc2077.aniclijava.data;

import android.util.Base64;

import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.AnimeDetail;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.Quality;
import com.minhmc2077.aniclijava.model.StreamInfo;
import com.minhmc2077.aniclijava.net.CronetHttp;
import com.minhmc2077.aniclijava.util.EpisodeNumbers;
import com.minhmc2077.aniclijava.util.HtmlText;
import com.minhmc2077.aniclijava.util.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adult-only source. The site is a WordPress theme: series pages hold the
 * episode links, each episode page points at a player, and the player exposes
 * an obfuscated list of progressive MP4 URLs.
 */
public final class WatchHentaiRepository implements AnimeSource {
    private static final String BASE = "https://watchhentai.net";
    private static final String HOME = BASE + "/";
    private static final String SEARCH = BASE + "/?s=%s";
    private static final String REFERER = BASE + "/";
    private static final String MP4 = "video/mp4";
    private static final long TRENDING_TTL = 10L * 60L * 60L * 1000L;
    private static final long SEARCH_TTL = 5L * 60L * 1000L;

    private static final Pattern CARD = Pattern.compile(
            "<article[^>]*class=\"[^\"]*item tvshows[^\"]*\"[^>]*>(.*?)</article>",
            Pattern.DOTALL);
    private static final Pattern CARD_HREF = Pattern.compile(
            "href=\"https?://[^/\"]+/series/([^\"/?]+)/?\"", Pattern.DOTALL);
    private static final Pattern CARD_TITLE = Pattern.compile(
            "<h3>\\s*<a[^>]*>([^<]+)</a>", Pattern.DOTALL);
    private static final Pattern CARD_POSTER = Pattern.compile(
            "data-src=\"(https?://[^\"]+)\"", Pattern.DOTALL);
    private static final Pattern CARD_YEAR = Pattern.compile(
            "buttonyear[^>]*>\\s*<span[^>]*>([^<]+)</span>", Pattern.DOTALL);

    private static final Pattern DETAIL_TITLE = Pattern.compile(
            "<div class=\"data\">\\s*<h1>(.*?)</h1>", Pattern.DOTALL);
    private static final Pattern DETAIL_POSTER = Pattern.compile(
            "itemprop=\"image\"\\s+src=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern DETAIL_DESC = Pattern.compile(
            "tv-single-synopsis__body\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern DETAIL_SCORE = Pattern.compile(
            "data-rating10=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern DETAIL_GENRES = Pattern.compile(
            "<div class=\"sgeneros\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern ANCHOR = Pattern.compile("<a [^>]*>([^<]+)</a>", Pattern.DOTALL);

    private static final Pattern EPISODE_CARD = Pattern.compile(
            "<a([^>]*tv-ep-card-link[^>]*)>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile("href=['\"]([^'\"]+)['\"]");
    private static final Pattern EPISODE_TITLE = Pattern.compile(
            "<span class=['\"]eptitle['\"]>\\s*Episode\\s*([^<]+?)\\s*</span>", Pattern.DOTALL);
    private static final Pattern EPISODE_TITLE_ATTR = Pattern.compile(
            "title=['\"]Episode\\s*([^'\"]+?)\\s+Watch", Pattern.DOTALL);

    private static final Pattern PRIMARY_PLAYER = Pattern.compile(
            "data-primary-player-url=\"([^\"]+)\"");
    private static final Pattern ALTERNATE_PLAYER = Pattern.compile(
            "data-alternate-player-url=\"([^\"]+)\"");
    private static final Pattern SOURCES = Pattern.compile(
            "var\\s+whJwSources\\s*=\\s*(\\[.*?\\]);", Pattern.DOTALL);
    private static final Pattern CONTENT_URL = Pattern.compile(
            "\"contentUrl\":\"([^\"]+)\"");
    private static final Pattern DURATION = Pattern.compile(
            "\"duration\":\"PT(?:([0-9]+)H)?(?:([0-9]+)M)?(?:([0-9]+)S)?\"");

    private final CronetHttp http;
    private final Map<String, List<Episode>> episodeCache = new ConcurrentHashMap<>();
    private final Map<String, AnimeDetail> detailCache = new ConcurrentHashMap<>();
    private final Map<String, TimedCache<List<Anime>>> searchCache = new ConcurrentHashMap<>();
    private volatile List<Anime> trendingCache;
    private volatile long trendingTime;

    public WatchHentaiRepository(CronetHttp http) {
        this.http = http;
    }

    @Override
    public List<Anime> trending(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && trendingCache != null && now - trendingTime < TRENDING_TTL) {
            return trendingCache;
        }
        List<Anime> list = parseCards(fetch(HOME, REFERER));
        if (list.isEmpty()) {
            throw new IOException("No adult anime found");
        }
        trendingCache = list;
        trendingTime = now;
        return list;
    }

    @Override
    public List<Anime> search(String query) throws IOException {
        String cleaned = query == null ? "" : query.trim();
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }
        String key = cleaned.toLowerCase(Locale.US);
        TimedCache<List<Anime>> cached = searchCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.time < SEARCH_TTL) {
            return cached.value;
        }
        String encoded;
        try {
            encoded = URLEncoder.encode(cleaned, "UTF-8");
        } catch (Exception impossible) {
            encoded = cleaned;
        }
        List<Anime> list = parseCards(fetch(String.format(Locale.US, SEARCH, encoded), REFERER));
        searchCache.put(key, new TimedCache<>(list, now));
        return list;
    }

    @Override
    public AnimeDetail detail(String animeId, boolean force) throws IOException {
        AnimeDetail cached = force ? null : detailCache.get(animeId);
        if (cached != null) {
            return cached;
        }
        String html = fetch(detailUrl(animeId), REFERER);
        String title = HtmlText.clean(HtmlText.group(DETAIL_TITLE, html, 1));
        if (title == null || title.isEmpty()) {
            title = HtmlText.clean(HtmlText.group(Pattern.compile("<h1>(.*?)</h1>",
                    Pattern.DOTALL), html, 1));
        }
        String poster = HtmlText.group(DETAIL_POSTER, html, 1);
        String description = HtmlText.unescape(HtmlText.group(DETAIL_DESC, html, 1));
        String score = HtmlText.group(DETAIL_SCORE, html, 1);
        String status = html.contains("<div class=\"buttonuncensured\"")
                ? "Uncensored"
                : html.contains("<div class=\"buttoncensured\"") ? "Censored" : "";

        StringBuilder genres = new StringBuilder();
        String genreBlock = HtmlText.group(DETAIL_GENRES, html, 1);
        if (genreBlock != null) {
            Matcher anchor = ANCHOR.matcher(genreBlock);
            while (anchor.find()) {
                String genre = HtmlText.clean(anchor.group(1));
                if (genre.isEmpty()) {
                    continue;
                }
                if (genres.length() > 0) {
                    genres.append(" • ");
                }
                genres.append(genre);
            }
        }

        AnimeDetail detail = new AnimeDetail(title, poster, description, score,
                status, genres.toString(), 0, 0);
        detailCache.put(animeId, detail);
        return detail;
    }

    @Override
    public List<Episode> episodes(String animeId, boolean force) throws IOException {
        List<Episode> cached = force ? null : episodeCache.get(animeId);
        if (cached != null) {
            return cached;
        }
        String html = fetch(detailUrl(animeId), REFERER);
        List<Episode> list = parseEpisodes(html);
        if (!list.isEmpty()) {
            episodeCache.put(animeId, list);
        }
        return list;
    }

    @Override
    public StreamInfo resolveStream(String animeId, Episode episode, String mode)
            throws IOException {
        if (episode == null || episode.id == null || episode.id.isEmpty()) {
            throw new IOException("Adult episode has no page");
        }
        String videoUrl = absolute(episode.id);
        String page = fetch(videoUrl, REFERER);
        String playerUrl = HtmlText.group(PRIMARY_PLAYER, page, 1);
        if (playerUrl == null || playerUrl.isEmpty()) {
            playerUrl = HtmlText.group(ALTERNATE_PLAYER, page, 1);
        }
        if (playerUrl == null || playerUrl.isEmpty()) {
            throw new IOException("Adult player not found");
        }

        String player = fetch(absolute(playerUrl), videoUrl);
        List<Quality> qualities = parseSources(player);
        long durationMs = parseDuration(player);

        if (qualities.isEmpty()) {
            String direct = HtmlText.group(CONTENT_URL, player, 1);
            if (direct != null && !direct.isEmpty()) {
                qualities.add(new Quality(heightLabel(direct, 0), parseHeight(direct), direct));
            }
        }
        if (qualities.isEmpty()) {
            throw new IOException("No adult video source for this episode");
        }

        Collections.sort(qualities, (first, second) ->
                Integer.compare(second.height, first.height));
        return new StreamInfo(qualities.get(0).url, null, REFERER, null,
                qualities, durationMs, MP4);
    }

    private List<Anime> parseCards(String html) {
        List<Anime> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher card = CARD.matcher(html);
        while (card.find()) {
            String block = card.group(1);
            Matcher href = CARD_HREF.matcher(block);
            if (!href.find()) {
                continue;
            }
            String slug = href.group(1);
            if (slug.isEmpty() || !seen.add(slug)) {
                continue;
            }
            String title = HtmlText.clean(HtmlText.group(CARD_TITLE, block, 1));
            if (title == null || title.isEmpty()) {
                title = slug;
            }
            String poster = HtmlText.group(CARD_POSTER, block, 1);
            String type = HtmlText.clean(HtmlText.group(CARD_YEAR, block, 1));
            result.add(new Anime(Settings.WATCHHENTAI_ID_PREFIX + slug, title, poster,
                    type, 0, 0, 0, 0));
        }
        return result;
    }

    private List<Episode> parseEpisodes(String html) {
        Map<String, Episode> result = new LinkedHashMap<>();
        Matcher card = EPISODE_CARD.matcher(html);
        while (card.find()) {
            String attributes = card.group(1);
            Matcher href = HREF.matcher(attributes);
            if (!href.find()) {
                continue;
            }
            String url = absolute(href.group(1));
            String number = HtmlText.clean(HtmlText.group(EPISODE_TITLE, card.group(2), 1));
            if (number == null || number.isEmpty()) {
                number = HtmlText.clean(HtmlText.group(EPISODE_TITLE_ATTR, attributes, 1));
            }
            if (number == null || number.isEmpty() || result.containsKey(number)) {
                continue;
            }
            result.put(number, new Episode(number, url));
        }
        List<Episode> list = new ArrayList<>(result.values());
        Collections.sort(list, (first, second) ->
                Double.compare(EpisodeNumbers.value(first.number),
                        EpisodeNumbers.value(second.number)));
        return list;
    }

    private List<Quality> parseSources(String player) throws IOException {
        List<Quality> result = new ArrayList<>();
        Matcher matcher = SOURCES.matcher(player);
        if (!matcher.find()) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(matcher.group(1));
            for (int i = 0; i < array.length(); i++) {
                JSONObject source = array.optJSONObject(i);
                if (source == null) {
                    continue;
                }
                String file = source.optString("file", "");
                if (file.isEmpty()) {
                    continue;
                }
                String url = decodeMedia(file);
                String label = source.optString("label", "Auto");
                int height = parseHeight(label);
                result.add(new Quality(height > 0 ? label : "Auto", height, url));
            }
        } catch (Exception error) {
            throw new IOException("Could not decode adult sources", error);
        }
        return result;
    }

    private static String decodeMedia(String encoded) {
        String value = encoded.replace('-', '+').replace('_', '/');
        while (value.length() % 4 != 0) {
            value += "=";
        }
        byte[] bytes = Base64.decode(value, Base64.DEFAULT);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= (byte) ((13 + i % 17) & 0xFF);
        }
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte swap = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = swap;
        }
        return new String(Base64.decode(bytes, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private static long parseDuration(String player) {
        Matcher matcher = DURATION.matcher(player);
        if (!matcher.find()) {
            return 0L;
        }
        long hours = parseLong(matcher.group(1));
        long minutes = parseLong(matcher.group(2));
        long seconds = parseLong(matcher.group(3));
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L;
    }

    private static long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int parseHeight(String value) {
        if (value == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("([0-9]{3,4})p?").matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String heightLabel(String url, int fallback) {
        int height = parseHeight(url.substring(url.lastIndexOf('/') + 1));
        return height > 0 ? height + "p" : fallback > 0 ? fallback + "p" : "Auto";
    }

    private String fetch(String url, String referer) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", referer);
        CronetHttp.Response response = http.get(url, headers, true);
        if (!response.isSuccessful()) {
            if (response.statusCode == 403 || response.statusCode == 503) {
                throw new IOException("Cloudflare blocked the request");
            }
            throw new IOException("HTTP " + response.statusCode);
        }
        String text = response.text();
        if (text.contains("Just a moment") || text.contains("<title>Just a moment")) {
            throw new IOException("Cloudflare blocked the request");
        }
        return text;
    }

    private static String detailUrl(String animeId) {
        String slug = animeId == null ? "" : animeId;
        if (slug.startsWith(Settings.WATCHHENTAI_ID_PREFIX)) {
            slug = slug.substring(Settings.WATCHHENTAI_ID_PREFIX.length());
        }
        if (slug.startsWith("http://") || slug.startsWith("https://")) {
            return slug;
        }
        while (slug.startsWith("/")) {
            slug = slug.substring(1);
        }
        while (slug.endsWith("/")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return BASE + "/series/" + slug + "/";
    }

    private static String absolute(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return BASE + (url.startsWith("/") ? url : "/" + url);
    }

    private static final class TimedCache<T> {
        final T value;
        final long time;

        TimedCache(T value, long time) {
            this.value = value;
            this.time = time;
        }
    }
}
