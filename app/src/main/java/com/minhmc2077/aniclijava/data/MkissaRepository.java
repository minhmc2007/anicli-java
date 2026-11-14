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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class MkissaRepository implements AnimeSource {
    private static final String API = "https://api.mkissa.net/api";
    private static final String SITE = "https://mkissa.to";
    private static final String BOOT_URL =
            "https://api.mkissa.net/client-crypto/v1/bootstrap?buildId=189&k=k7";
    private static final String EPISODE_HASH =
            "670bbf38d0868f446e2346c1e956ca2c40c416e733ca248fd54e04f1c8b99145";
    private static final String LANE = "k7";
    private static final int BOOT_BUILD = 189;
    private static final long WEEK_MS = 604800000L;
    private static final String REFERER = SITE + "/";
    private static final String MP4 = "video/mp4";
    private static final byte[] MASK = mask(hex(
            "532f1b5cfbb3bec1763a06000d7919f92c94dec5626aa52e616276f4070b06ea"));
    private static final byte[] BOOT_PREFIX = "iGmNgPGdi:".getBytes(StandardCharsets.UTF_8);
    private static final long TRENDING_TTL = 10L * 60L * 1000L;
    private static final long SEARCH_TTL = 5L * 60L * 1000L;

    private static final String SHOW_EDGE = """
            _id name englishName nativeName slugTime thumbnail
            tbObj { u sm md ts }
            lastEpisodeInfo lastEpisodeDate type season score airedStart
            availableEpisodes episodeDuration episodeCount
            lastUpdateEnd isAdult
            """;

    private static final String Q_SEARCH = """
            query($search: SearchInput, $limit: Int, $page: Int,
                  $translationType: VaildTranslationTypeEnumType,
                  $countryOrigin: VaildCountryOriginEnumType) {
            shows(search: $search, limit: $limit, page: $page,
                  translationType: $translationType, countryOrigin: $countryOrigin) {
            pageInfo { total }
            edges { %s }
            }
            }
            """.formatted(SHOW_EDGE);

    private static final String Q_SHOW = """
            query($_id: String!, $search: SearchInput) {
            show(_id: $_id, search: $search) {
            %s
            description availableEpisodesDetail malId status genres
            }
            }
            """.formatted(SHOW_EDGE);

    private static final String Q_EPISODE_INFOS = """
            query($showId: String!, $episodeNumStart: Float!, $episodeNumEnd: Float!) {
            episodeInfos(showId: $showId, episodeNumStart: $episodeNumStart,
                         episodeNumEnd: $episodeNumEnd) {
            episodeIdNum vidInforssub vidInforsdub
            }
            }
            """;

    private final CronetHttp http;
    private final Map<String, Show> shows = new ConcurrentHashMap<>();
    private final Map<String, TimedCache<List<Anime>>> searchCache = new ConcurrentHashMap<>();
    private volatile List<Anime> trendingCache;
    private volatile long trendingTime;
    private volatile int apiBuild = 190;
    private volatile Boot boot;

    public MkissaRepository(CronetHttp http) {
        this.http = http;
    }

    @Override
    public List<Anime> trending(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && trendingCache != null && now - trendingTime < TRENDING_TTL) {
            return trendingCache;
        }
        List<Anime> list = queryShows("", 30);
        if (list.isEmpty()) {
            throw new IOException("No Mkissa trending anime found");
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
        List<Anime> list = queryShows(cleaned, 40);
        searchCache.put(key, new TimedCache<>(list, now));
        return list;
    }

    private List<Anime> queryShows(String query, int limit) throws IOException {
        JSONObject search = json(
                "allowAdult", true,
                "allowUnknown", false,
                "denyEcchi", false,
                "query", query);

        JSONObject variables = json(
                "search", search,
                "limit", limit,
                "page", 1,
                "translationType", "sub",
                "countryOrigin", "ALL");

        JSONObject data = plain(Q_SEARCH, variables);
        JSONObject shows = data.optJSONObject("shows");
        JSONArray edges = shows == null ? null : shows.optJSONArray("edges");
        List<Anime> result = new ArrayList<>();
        if (edges != null) {
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.optJSONObject(i);
                if (edge != null) {
                    result.add(anime(edge, i + 1));
                }
            }
        }
        return result;
    }

    @Override
    public AnimeDetail detail(String animeId, boolean force) throws IOException {
        return show(showId(animeId), force).detail;
    }

    @Override
    public List<Episode> episodes(String animeId, boolean force) throws IOException {
        Show value = show(showId(animeId), force);
        LinkedHashSet<String> numbers = new LinkedHashSet<>();
        numbers.addAll(value.sub);
        numbers.addAll(value.dub);
        List<Episode> result = new ArrayList<>();
        for (String number : numbers) {
            if (!number.isEmpty()) {
                result.add(new Episode(number, number));
            }
        }
        Collections.sort(result, (first, second) ->
                Double.compare(EpisodeNumbers.value(first.number),
                        EpisodeNumbers.value(second.number)));
        return result;
    }

    @Override
    public StreamInfo resolveStream(String animeId, Episode episode, String mode)
            throws IOException {
        String showId = showId(animeId);
        Show show = show(showId, false);
        String translation = "dub".equalsIgnoreCase(mode) ? "dub" : "sub";
        String direct = signedEpisodeUrl(showId, episode.number, translation);

        JSONObject info = episodeInfo(showId, episode.number, translation);
        int height = info == null ? 0 : parseHeight(info.opt("vidResolution"));
        long durationMs = info == null ? 0L : optDurationMs(info, "vidDuration");

        Quality quality = new Quality(height > 0 ? height + "p" : "Auto", height, direct);
        return new StreamInfo(direct, null, REFERER, show.malId,
                Collections.singletonList(quality), durationMs, MP4);
    }

    private String signedEpisodeUrl(String showId, String episode, String translation)
            throws IOException {
        JSONObject variables = json(
                "showId", showId,
                "translationType", translation,
                "episodeString", episode);

        JSONObject root = signedEpisode(variables);
        JSONObject data = root.optJSONObject("data");
        JSONObject value = data == null ? null : data.optJSONObject("episode");
        String url = findDirectUrl(value);
        if (url == null) {
            throw new IOException("Mkissa has no playable source for this episode");
        }
        return url;
    }

    private JSONObject episodeInfo(String showId, String episode, String translation)
            throws IOException {
        double number = EpisodeNumbers.value(episode);
        JSONObject variables = json(
                "showId", showId,
                "episodeNumStart", number,
                "episodeNumEnd", number);

        JSONObject data = plain(Q_EPISODE_INFOS, variables);
        JSONArray infos = data.optJSONArray("episodeInfos");
        if (infos == null || infos.length() == 0) {
            return null;
        }
        JSONObject info = infos.optJSONObject(0);
        if (info == null) {
            return null;
        }
        JSONObject value = info.optJSONObject("dub".equals(translation)
                ? "vidInforsdub"
                : "vidInforssub");
        if (value == null) {
            throw new IOException("No Mkissa " + translation + " source for this episode");
        }
        return value;
    }

    private Show show(String showId, boolean force) throws IOException {
        Show cached = force ? null : shows.get(showId);
        if (cached != null) {
            return cached;
        }

        JSONObject variables = json(
                "_id", showId,
                "search", json(
                        "allowAdult", true,
                        "allowUnknown", false));

        JSONObject data = plain(Q_SHOW, variables);
        JSONObject object = data.optJSONObject("show");
        if (object == null) {
            throw new IOException("Mkissa show not found");
        }

        String title = first(object.optString("name"), object.optString("englishName"));
        String poster = object.optString("thumbnail", "");
        String description = HtmlText.unescape(object.optString("description", ""));
        String score = object.isNull("score") ? "" : String.valueOf(object.opt("score"));
        String status = object.optString("status", "");
        JSONArray genres = object.optJSONArray("genres");
        StringBuilder genreText = new StringBuilder();
        if (genres != null) {
            for (int i = 0; i < genres.length(); i++) {
                String genre = genres.optString(i, "");
                if (genre.isEmpty()) {
                    continue;
                }
                if (genreText.length() > 0) {
                    genreText.append(" • ");
                }
                genreText.append(genre);
            }
        }

        JSONObject detail = object.optJSONObject("availableEpisodesDetail");
        List<String> sub = strings(detail == null ? null : detail.optJSONArray("sub"));
        List<String> dub = strings(detail == null ? null : detail.optJSONArray("dub"));
        AnimeDetail animeDetail = new AnimeDetail(title, poster, description, score, status,
                genreText.toString(), sub.size(), dub.size());
        Show value = new Show(animeDetail, object.optString("malId", ""), sub, dub);
        shows.put(showId, value);
        return value;
    }

    private JSONObject signedEpisode(JSONObject variables) throws IOException {
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        candidates.add(apiBuild);
        candidates.add(190);
        candidates.add(191);
        candidates.add(189);
        candidates.add(192);
        candidates.add(188);
        candidates.add(193);

        IOException failure = null;
        for (Integer candidate : candidates) {
            JSONObject root = signedRequest(variables, candidate);
            String error = errorMessage(root);
            if (error == null) {
                apiBuild = candidate;
                return root;
            }
            if (error.contains("NEED_CAPTCHA")) {
                throw new IOException("Mkissa needs a captcha");
            }
            if (!error.contains("AA_CRYPTO_MISSING_BUILD")) {
                throw new IOException(error);
            }
            failure = new IOException(error);
        }
        throw failure == null ? new IOException("Mkissa crypto failed") : failure;
    }

    private JSONObject signedRequest(JSONObject variables, int build) throws IOException {
        Boot current = boot();
        String aaReq = aaReq(current, EPISODE_HASH, build,
                (System.currentTimeMillis() / 300000L) * 300000L);

        JSONObject extensions = json(
                "persistedQuery", json(
                        "version", 1,
                        "sha256Hash", EPISODE_HASH),
                "k", LANE,
                "aaReq", aaReq);

        JSONObject body = json(
                "variables", variables,
                "extensions", extensions);

        return request(body, build);
    }

    private Boot boot() throws IOException {
        Boot cached = boot;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (boot != null) {
                return boot;
            }
            long epoch = System.currentTimeMillis() / WEEK_MS;
            Map<String, String> headers = headers();
            headers.put("x-build-id", String.valueOf(BOOT_BUILD));
            headers.put("x-aa-boot", bootToken(epoch));

            CronetHttp.Response response = http.get(BOOT_URL, headers, true);
            if (!response.isSuccessful()) {
                throw new IOException("Mkissa bootstrap HTTP " + response.statusCode);
            }
            JSONObject json = parse(response);
            String partB = json.optString("partB", "");
            byte[] part = Base64.decode(partB, Base64.DEFAULT);
            if (part.length < 32) {
                throw new IOException("Mkissa bootstrap rejected");
            }
            byte[] key = new byte[32];
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (part[i] ^ MASK[i % MASK.length]);
            }
            boot = new Boot(json.optLong("epoch", epoch), key);
            return boot;
        }
    }

    private String aaReq(Boot value, String queryHash, int build, long timestamp)
            throws IOException {
        String seed = value.epoch + ":" + build + ":" + queryHash + ":" + timestamp
                + ":" + LANE;
        byte[] iv = Arrays.copyOf(sha256(seed), 12);

        JSONObject payload = json(
                "v", 1,
                "ts", timestamp,
                "epoch", value.epoch,
                "buildId", build,
                "qh", queryHash,
                "k", LANE);

        byte[] encrypted;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(value.key, "AES"),
                    new GCMParameterSpec(128, iv));
            encrypted = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IOException("Mkissa crypto failed", error);
        }

        byte[] raw = new byte[1 + iv.length + encrypted.length];
        raw[0] = 1;
        System.arraycopy(iv, 0, raw, 1, iv.length);
        System.arraycopy(encrypted, 0, raw, 1 + iv.length, encrypted.length);
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    private String bootToken(long epoch) throws IOException {
        try {
            byte[] stage = hmac(MASK,
                    (new String(BOOT_PREFIX, StandardCharsets.UTF_8) + BOOT_BUILD)
                            .getBytes(StandardCharsets.UTF_8));
            String message = epoch + "|" + LANE + "|mkissa|mkissa.to|" + BOOT_BUILD;
            return hex(hmac(stage, message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IOException("Mkissa crypto failed", error);
        }
    }

    private JSONObject plain(String query, JSONObject variables) throws IOException {
        JSONObject body = json(
                "query", query,
                "variables", variables);
        JSONObject root = request(body, null);
        String error = errorMessage(root);
        if (error != null) {
            throw new IOException(error);
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new IOException("Mkissa returned no data");
        }
        return data;
    }

    private JSONObject request(JSONObject body, Integer build) throws IOException {
        Map<String, String> headers = headers();
        if (build != null) {
            headers.put("x-build-id", String.valueOf(build));
        }
        CronetHttp.Response response = http.request("POST", API, headers,
                body.toString(), "application/json");
        if (!response.isSuccessful()) {
            throw new IOException("Mkissa HTTP " + response.statusCode + ": "
                    + snippet(response.text()));
        }
        return decrypt(parse(response));
    }

    private JSONObject decrypt(JSONObject root) throws IOException {
        JSONObject data = root.optJSONObject("data");
        String payload = data == null ? "" : data.optString("tobeparsed", "");
        if (payload.isEmpty()) {
            return root;
        }
        try {
            String padded = payload;
            while (padded.length() % 4 != 0) {
                padded += "=";
            }
            byte[] raw = Base64.decode(padded, Base64.DEFAULT);
            byte[] iv = Arrays.copyOfRange(raw, 1, 13);
            byte[] cipherText = Arrays.copyOfRange(raw, 13, raw.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(boot().key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(cipherText);

            JSONObject inner = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (!inner.has("data")) {
                return json("data", inner);
            }
            return inner;
        } catch (JSONException | GeneralSecurityException error) {
            throw new IOException("Could not decrypt Mkissa response", error);
        }
    }

    private static JSONObject parse(CronetHttp.Response response) throws IOException {
        try {
            return new JSONObject(response.text());
        } catch (JSONException error) {
            throw new IOException("Could not parse Mkissa response: "
                    + snippet(response.text()), error);
        }
    }

    private static String snippet(String value) {
        String clean = HtmlText.clean(value);
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    private static String errorMessage(JSONObject root) {
        JSONArray errors = root.optJSONArray("errors");
        if (errors == null || errors.length() == 0) {
            return null;
        }
        JSONObject first = errors.optJSONObject(0);
        return first == null ? "Mkissa request failed"
                : first.optString("message", "Mkissa request failed");
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", CronetHttp.USER_AGENT);
        headers.put("Origin", SITE);
        headers.put("Referer", REFERER);
        return headers;
    }

    private static Anime anime(JSONObject edge, int rank) {
        JSONObject available = edge.optJSONObject("availableEpisodes");
        int sub = available == null ? 0 : available.optInt("sub", 0);
        int dub = available == null ? 0 : available.optInt("dub", 0);
        String title = first(edge.optString("name"), edge.optString("englishName"));
        String poster = edge.optString("thumbnail", "");
        return new Anime(Settings.MKISSA_ID_PREFIX + edge.optString("_id", ""), title,
                poster, edge.optString("type", ""), edge.optInt("episodeCount", 0),
                sub, dub, rank);
    }

    private static String findDirectUrl(Object node) {
        List<String> urls = new ArrayList<>();
        collectUrls(node, urls);
        for (String url : urls) {
            if (url.contains("fast4speed")) {
                return url;
            }
        }
        for (String url : urls) {
            if (url.contains("youtube-anime")) {
                return url;
            }
        }
        for (String url : urls) {
            if (url.contains(".mp4") || url.contains(".m3u8")) {
                return url;
            }
        }
        return null;
    }

    private static void collectUrls(Object node, List<String> urls) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectUrls(object.opt(keys.next()), urls);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectUrls(array.opt(i), urls);
            }
        } else if (node instanceof String) {
            String value = (String) node;
            if (value.startsWith("http")) {
                urls.add(value);
            } else if (value.startsWith("//")) {
                urls.add("https:" + value);
            }
        }
    }

    private static List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static String showId(String animeId) {
        if (animeId == null) {
            return "";
        }
        return animeId.startsWith(Settings.MKISSA_ID_PREFIX)
                ? animeId.substring(Settings.MKISSA_ID_PREFIX.length())
                : animeId;
    }

    private static int parseHeight(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return 0;
        }
        String text = String.valueOf(value).replaceAll("[^0-9]", "");
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long optDurationMs(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return 0L;
        }
        try {
            return Math.round(Double.parseDouble(String.valueOf(value)) * 1000d);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static JSONObject json(Object... entries) throws IOException {
        JSONObject object = new JSONObject();
        try {
            for (int i = 0; i + 1 < entries.length; i += 2) {
                object.put((String) entries[i], entries[i + 1]);
            }
        } catch (JSONException error) {
            throw new IOException("Mkissa request failed", error);
        }
        return object;
    }

    private static String first(String first, String second) {
        return first == null || first.isEmpty() ? (second == null ? "" : second) : first;
    }

    private static byte[] sha256(String value) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IOException("Mkissa crypto failed", error);
        }
    }

    private static byte[] hmac(byte[] key, byte[] value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static byte[] mask(byte[] value) {
        byte[] result = value.clone();
        for (int i = 0; i < result.length; i++) {
            result[i] ^= 12;
        }
        return result;
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return builder.toString();
    }

    private static final class Show {
        final AnimeDetail detail;
        final String malId;
        final List<String> sub;
        final List<String> dub;

        Show(AnimeDetail detail, String malId, List<String> sub, List<String> dub) {
            this.detail = detail;
            this.malId = malId;
            this.sub = sub;
            this.dub = dub;
        }
    }

    private static final class Boot {
        final long epoch;
        final byte[] key;

        Boot(long epoch, byte[] key) {
            this.epoch = epoch;
            this.key = key;
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
