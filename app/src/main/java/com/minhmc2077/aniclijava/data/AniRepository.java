package com.minhmc2077.aniclijava.data;

import android.util.Base64;

import com.minhmc2077.aniclijava.model.Anime;
import com.minhmc2077.aniclijava.model.AnimeDetail;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.Quality;
import com.minhmc2077.aniclijava.model.StreamInfo;
import com.minhmc2077.aniclijava.net.CronetHttp;
import com.minhmc2077.aniclijava.util.HtmlText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
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
 * Port of the scraping logic in ani-cli 5.1.7.
 *
 * All requests use the bundled Cronet engine. The parser deliberately follows
 * the same endpoints and the same ZokoAnime embed deobfuscation as the shell
 * script, but uses Java regex/JSON instead of sed/awk.
 */
public final class AniRepository {
    public static final String BASE = "https://hianime.at";
    private static final String HOME = BASE + "/home";
    private static final String SEARCH = BASE + "/search?keyword=%s";
    private static final String EPISODES = BASE + "/api/theme/episode/list/%s";
    private static final String SERVERS = BASE + "/api/theme/episode/servers?episodeId=%s";

    private static final String EMBED_KEY = "otaku-embed-v1";
    private static final long TRENDING_TTL = 10L * 60L * 1000L;
    private static final long SEARCH_TTL = 5L * 60L * 1000L;

    private static final Pattern CARD_NAME = Pattern.compile(
            "<h3 class=\"film-name\">\\s*<a href=\"[^\"]*/([^\"/?]+)\"\\s*title=\"([^\"]*)\"",
            Pattern.DOTALL);
    private static final Pattern IMG = Pattern.compile("<img\\s+src=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern TYPE = Pattern.compile("<span class=\"fdi-item\">([^<]*)</span>");
    private static final Pattern EPS = Pattern.compile("tick-item tick-eps\">(\\d+)");
    private static final Pattern SUB = Pattern.compile("tick-item tick-sub\">\\s*(?:<[^>]+>\\s*)*(\\d+)");
    private static final Pattern DUB = Pattern.compile("tick-item tick-dub\">\\s*(?:<[^>]+>\\s*)*(\\d+)");

    private static final Pattern TRENDING_TITLE = Pattern.compile(
            "<div class=\"film-title dynamic-name\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern TRENDING_HREF = Pattern.compile(
            "<a href=\"https?://[^/]+/([^\"]+)\"\\s+class=\"film-poster\"", Pattern.DOTALL);
    private static final Pattern TRENDING_RANK = Pattern.compile(
            "<div class=\"number\">\\s*<span>(\\d+)</span>", Pattern.DOTALL);

    private static final Pattern DETAIL_TITLE = Pattern.compile(
            "<h2 class=\"film-name dynamic-name\"[^>]*>(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern DETAIL_POSTER = Pattern.compile(
            "<div class=\"film-poster\">\\s*<img src=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern DETAIL_DESC = Pattern.compile(
            "<div class=\"film-description m-hide\">\\s*<div class=\"text\">(.*?)</div>",
            Pattern.DOTALL);
    private static final Pattern DETAIL_SCORE = Pattern.compile(
            "<span class=\"item-head\">MAL Score:</span>\\s*<span class=\"name\">([^<]*)</span>",
            Pattern.DOTALL);
    private static final Pattern DETAIL_STATUS = Pattern.compile(
            "<span class=\"item-head\">Status:</span>\\s*<span class=\"name\">([^<]*)</span>",
            Pattern.DOTALL);
    private static final Pattern DETAIL_GENRES = Pattern.compile(
            "<span class=\"item-head\">Genres:</span>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern ANCHOR = Pattern.compile("<a [^>]*>([^<]+)</a>", Pattern.DOTALL);

    private static final Pattern EPISODE_PAIR = Pattern.compile(
            "data-number=\"([^\"]*)\"\\s+data-id=\"([0-9]+)\"", Pattern.DOTALL);
    private static final Pattern SERVER_ITEM = Pattern.compile(
            "<div[^>]*server-item[^>]*>", Pattern.DOTALL);
    private static final Pattern BLOB = Pattern.compile("window\\.__P=\"([^\"]*)\"");
    private static final Pattern MAL_ID = Pattern.compile("/mal/([0-9]+)/");
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=\\d+x(\\d+)");

    private final CronetHttp http;
    private final Map<String, List<Episode>> episodeCache = new ConcurrentHashMap<>();
    private final Map<String, AnimeDetail> detailCache = new ConcurrentHashMap<>();
    private final Map<String, TimedCache<List<Anime>>> searchCache = new ConcurrentHashMap<>();
    private volatile List<Anime> trendingCache;
    private volatile long trendingTime;

    public AniRepository(CronetHttp http) {
        this.http = http;
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    public List<Anime> trending(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && trendingCache != null && now - trendingTime < TRENDING_TTL) {
            return trendingCache;
        }

        String html = fetch(HOME);
        List<Anime> list = parseTrending(html);
        if (list.isEmpty()) {
            // The home page layout changes occasionally. Falling back to the
            // normal card parser still gives the user something useful.
            list = parseCards(html);
        }
        if (list.isEmpty()) {
            throw new IOException("No trending anime found");
        }
        trendingCache = list;
        trendingTime = now;
        return list;
    }

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
        String html = fetch(String.format(Locale.US, SEARCH, encoded));
        List<Anime> list = parseCards(html);
        searchCache.put(key, new TimedCache<>(list, now));
        return list;
    }

    public List<Episode> episodes(String animeId) throws IOException {
        return episodes(animeId, false);
    }

    public List<Episode> episodes(String animeId, boolean force) throws IOException {
        List<Episode> cached = force ? null : episodeCache.get(animeId);
        if (cached != null) {
            return cached;
        }

        String numericId = numericSuffix(animeId);
        String body = fetch(String.format(Locale.US, EPISODES, numericId));
        String html;
        try {
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("status", true)) {
                throw new IOException("Episode list unavailable");
            }
            html = json.optString("html", "");
        } catch (JSONException error) {
            throw new IOException("Could not parse episode list", error);
        }

        List<Episode> list = parseEpisodes(html);
        // Don't cache empty parses.
        if (!list.isEmpty()) {
            episodeCache.put(animeId, list);
        }
        return list;
    }

    public AnimeDetail detail(String animeId) throws IOException {
        AnimeDetail cached = detailCache.get(animeId);
        if (cached != null) {
            return cached;
        }
        String html = fetch(BASE + "/" + animeId);
        AnimeDetail detail = parseDetail(html);
        detailCache.put(animeId, detail);
        return detail;
    }

    public StreamInfo resolveStream(String episodeId, String mode) throws IOException {
        String requestedMode = "dub".equalsIgnoreCase(mode) ? "dub" : "sub";
        String serversBody = fetch(String.format(Locale.US, SERVERS, episodeId));

        String serversHtml;
        try {
            JSONObject json = new JSONObject(serversBody);
            if (!json.optBoolean("status", true)) {
                throw new IOException("Server list unavailable");
            }
            serversHtml = json.optString("html", "");
        } catch (JSONException error) {
            throw new IOException("Could not parse server list", error);
        }

        String hash = findZokoHash(serversHtml, requestedMode);
        if (hash == null) {
            throw new IOException("No ZokoAnime source for " + requestedMode);
        }

        String embedUrl;
        try {
            embedUrl = new String(Base64.decode(hash, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (Exception error) {
            throw new IOException("Invalid embed hash", error);
        }
        if (embedUrl.isEmpty()) {
            throw new IOException("Empty embed URL");
        }

        String referer = origin(embedUrl);
        String malId = HtmlText.group(MAL_ID, embedUrl, 1);
        // The embed host rejects requests without a Referer (same as ani-cli).
        Map<String, String> embedHeaders = new HashMap<>();
        embedHeaders.put("Referer", BASE + "/");
        String embedHtml = fetch(embedUrl, embedHeaders);
        String blob = HtmlText.group(BLOB, embedHtml, 1);
        if (blob == null || blob.isEmpty()) {
            throw new IOException("Stream blob not found");
        }

        String streamJson = deobfuscate(blob);
        String masterUrl;
        String subtitleUrl = null;
        try {
            JSONObject stream = new JSONObject(streamJson);
            masterUrl = stream.optString("src", "");
            JSONArray subtitles = stream.optJSONArray("subtitles");
            if (subtitles != null && subtitles.length() > 0) {
                JSONObject chosen = null;
                for (int i = 0; i < subtitles.length(); i++) {
                    JSONObject subtitle = subtitles.optJSONObject(i);
                    if (subtitle != null && subtitle.optBoolean("default", false)) {
                        chosen = subtitle;
                        break;
                    }
                }
                if (chosen == null) {
                    chosen = subtitles.optJSONObject(0);
                }
                if (chosen != null) {
                    subtitleUrl = chosen.optString("src", null);
                }
            }
        } catch (JSONException error) {
            throw new IOException("Could not parse stream data", error);
        }

        if (masterUrl == null || masterUrl.isEmpty()) {
            throw new IOException("Master playlist not found");
        }

        Map<String, String> playlistHeaders = new HashMap<>();
        playlistHeaders.put("Referer", referer);
        playlistHeaders.put("Origin", referer.substring(0, Math.max(0, referer.length() - 1)));
        String playlist = fetch(masterUrl, playlistHeaders);
        List<Quality> qualities = parseQualities(playlist, masterUrl);

        return new StreamInfo(masterUrl, subtitleUrl, referer, malId, qualities);
    }

    // ---------------------------------------------------------------------
    // Parsers
    // ---------------------------------------------------------------------

    private List<Anime> parseTrending(String html) {
        int start = html.indexOf("<div id=\"anime-trending\">");
        if (start < 0) {
            return Collections.emptyList();
        }
        int end = html.indexOf("<div id=\"anime-featured\">", start);
        String section = html.substring(start, end > start ? end : html.length());

        List<Anime> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int from = 0;
        while (true) {
            int blockStart = section.indexOf("<div class=\"swiper-slide\">", from);
            if (blockStart < 0) {
                break;
            }
            int blockEnd = section.indexOf("<div class=\"swiper-slide\">", blockStart + 1);
            if (blockEnd < 0) {
                blockEnd = section.length();
            }
            String block = section.substring(blockStart, blockEnd);
            from = blockEnd;

            Matcher href = TRENDING_HREF.matcher(block);
            if (!href.find()) {
                continue;
            }
            String id = href.group(1);
            if (id.isEmpty() || seen.contains(id)) {
                continue;
            }

            String title = null;
            Matcher titleMatcher = TRENDING_TITLE.matcher(block);
            if (titleMatcher.find()) {
                title = HtmlText.clean(titleMatcher.group(1));
            }
            if (title == null || title.isEmpty()) {
                title = id;
            }

            String poster = null;
            Matcher img = IMG.matcher(block);
            if (img.find()) {
                poster = img.group(1);
            }

            int rank = 0;
            Matcher rankMatcher = TRENDING_RANK.matcher(block);
            if (rankMatcher.find()) {
                try {
                    rank = Integer.parseInt(rankMatcher.group(1));
                } catch (NumberFormatException ignored) {
                }
            }

            seen.add(id);
            result.add(new Anime(id, title, poster, "", 0, 0, 0, rank));
        }
        return result;
    }

    private List<Anime> parseCards(String html) {
        List<Anime> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int from = 0;
        while (true) {
            int start = html.indexOf("<div class=\"flw-item", from);
            if (start < 0) {
                break;
            }
            int next = html.indexOf("<div class=\"flw-item", start + 1);
            String block = html.substring(start, next < 0 ? html.length() : next);
            from = next < 0 ? html.length() : next;

            Matcher name = CARD_NAME.matcher(block);
            if (!name.find()) {
                continue;
            }
            String id = name.group(1);
            String title = HtmlText.unescape(name.group(2));
            if (id.isEmpty() || title.isEmpty() || seen.contains(id)) {
                continue;
            }

            String poster = null;
            Matcher img = IMG.matcher(block);
            if (img.find()) {
                poster = img.group(1);
            }

            String type = "";
            Matcher typeMatcher = TYPE.matcher(block);
            if (typeMatcher.find()) {
                type = HtmlText.clean(typeMatcher.group(1));
            }

            int episodes = intGroup(EPS, block);
            int sub = intGroup(SUB, block);
            int dub = intGroup(DUB, block);

            seen.add(id);
            result.add(new Anime(id, title, poster, type, episodes, sub, dub, 0));
        }
        return result;
    }

    private List<Episode> parseEpisodes(String html) {
        Map<String, Episode> result = new LinkedHashMap<>();
        Matcher matcher = EPISODE_PAIR.matcher(html);
        while (matcher.find()) {
            String number = HtmlText.unescape(matcher.group(1));
            String id = matcher.group(2);
            if (!number.isEmpty() && !id.isEmpty() && !result.containsKey(id)) {
                result.put(id, new Episode(number, id));
            }
        }
        return new ArrayList<>(result.values());
    }

    private AnimeDetail parseDetail(String html) {
        String title = HtmlText.clean(HtmlText.group(DETAIL_TITLE, html, 1));
        String poster = HtmlText.group(DETAIL_POSTER, html, 1);
        String description = HtmlText.unescape(HtmlText.group(DETAIL_DESC, html, 1));
        String score = HtmlText.clean(HtmlText.group(DETAIL_SCORE, html, 1));
        String status = HtmlText.clean(HtmlText.group(DETAIL_STATUS, html, 1));

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

        return new AnimeDetail(title, poster, description, score, status, genres.toString());
    }

    private String findZokoHash(String serversHtml, String mode) {
        Matcher matcher = SERVER_ITEM.matcher(serversHtml);
        while (matcher.find()) {
            String attributes = matcher.group();
            String type = HtmlText.attr(attributes, "data-type");
            String name = HtmlText.attr(attributes, "data-server-name");
            String hash = HtmlText.attr(attributes, "data-hash");
            if (mode.equalsIgnoreCase(type) && "ZokoAnime".equalsIgnoreCase(name)
                    && hash != null && !hash.isEmpty()) {
                return hash;
            }
        }
        return null;
    }

    private static String deobfuscate(String blob) throws IOException {
        try {
            byte[] data = Base64.decode(blob, Base64.DEFAULT);
            byte[] key = EMBED_KEY.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < data.length; i++) {
                data[i] ^= key[i % key.length];
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IOException("Could not deobfuscate stream blob", error);
        }
    }

    private static List<Quality> parseQualities(String playlist, String masterUrl) {
        List<Quality> result = new ArrayList<>();
        String[] lines = playlist.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                continue;
            }
            String url = null;
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.isEmpty() || candidate.startsWith("#")) {
                    continue;
                }
                url = resolveUrl(masterUrl, candidate);
                break;
            }
            if (url == null) {
                continue;
            }
            int height = 0;
            Matcher resolution = RESOLUTION.matcher(line);
            if (resolution.find()) {
                try {
                    height = Integer.parseInt(resolution.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            String label = height > 0 ? height + "p" : "Auto";
            result.add(new Quality(label, height, url));
        }

        if (result.isEmpty()) {
            result.add(new Quality("Auto", 0, masterUrl));
            return result;
        }

        Collections.sort(result, (a, b) -> Integer.compare(b.height, a.height));
        Map<String, Quality> deduped = new LinkedHashMap<>();
        for (Quality quality : result) {
            deduped.put(quality.url, quality);
        }
        return new ArrayList<>(deduped.values());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String fetch(String url) throws IOException {
        return fetch(url, null);
    }

    private String fetch(String url, Map<String, String> headers) throws IOException {
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

    private static String numericSuffix(String animeId) {
        int dash = animeId.lastIndexOf('-');
        return dash >= 0 ? animeId.substring(dash + 1) : animeId;
    }

    private static int intGroup(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String origin(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            if (scheme != null && authority != null) {
                return scheme + "://" + authority + "/";
            }
        } catch (Exception ignored) {
        }
        int schemeEnd = url.indexOf("://");
        int slash = schemeEnd >= 0 ? url.indexOf('/', schemeEnd + 3) : -1;
        return slash > 0 ? url.substring(0, slash + 1) : url;
    }

    private static String resolveUrl(String baseUrl, String reference) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }
        if (reference.startsWith("http://") || reference.startsWith("https://")) {
            return reference;
        }
        try {
            URI base = URI.create(baseUrl);
            return base.resolve(reference).toString();
        } catch (Exception ignored) {
            if (reference.startsWith("//")) {
                return "https:" + reference;
            }
            int slash = baseUrl.lastIndexOf('/');
            String base = slash >= 0 ? baseUrl.substring(0, slash + 1) : baseUrl;
            if (reference.startsWith("/")) {
                int schemeEnd = baseUrl.indexOf("://");
                int hostEnd = schemeEnd >= 0 ? baseUrl.indexOf('/', schemeEnd + 3) : -1;
                if (hostEnd > 0) {
                    return baseUrl.substring(0, hostEnd) + reference;
                }
            }
            return base + reference;
        }
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
